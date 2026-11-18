#include "LinuxMprisService.h"

#if !defined(_WIN32) && !defined(__APPLE__)

#include <gio/gio.h>
#include <glib.h>

#include <algorithm>
#include <cctype>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <unistd.h>

namespace {

constexpr const char* MPRIS_OBJECT_PATH = "/org/mpris/MediaPlayer2";
constexpr const char* MPRIS_ROOT_IFACE = "org.mpris.MediaPlayer2";
constexpr const char* MPRIS_PLAYER_IFACE = "org.mpris.MediaPlayer2.Player";
constexpr const char* MPRIS_PRIMARY_BUS = "org.mpris.MediaPlayer2.zuna";
constexpr const char* NO_TRACK_OBJECT_PATH = "/org/mpris/MediaPlayer2/TrackList/NoTrack";

constexpr const char* MPRIS_INTROSPECTION_XML = R"XML(
<node>
  <interface name="org.mpris.MediaPlayer2">
    <method name="Raise"/>
    <method name="Quit"/>
    <property name="CanQuit" type="b" access="read"/>
    <property name="CanRaise" type="b" access="read"/>
    <property name="HasTrackList" type="b" access="read"/>
    <property name="Identity" type="s" access="read"/>
    <property name="DesktopEntry" type="s" access="read"/>
    <property name="SupportedUriSchemes" type="as" access="read"/>
    <property name="SupportedMimeTypes" type="as" access="read"/>
  </interface>
  <interface name="org.mpris.MediaPlayer2.Player">
    <method name="Next"/>
    <method name="Previous"/>
    <method name="Pause"/>
    <method name="PlayPause"/>
    <method name="Stop"/>
    <method name="Play"/>
    <method name="Seek">
      <arg direction="in" name="Offset" type="x"/>
    </method>
    <method name="SetPosition">
      <arg direction="in" name="TrackId" type="o"/>
      <arg direction="in" name="Position" type="x"/>
    </method>
    <method name="OpenUri">
      <arg direction="in" name="Uri" type="s"/>
    </method>
    <signal name="Seeked">
      <arg name="Position" type="x"/>
    </signal>
    <property name="PlaybackStatus" type="s" access="read"/>
    <property name="LoopStatus" type="s" access="readwrite"/>
    <property name="Rate" type="d" access="readwrite"/>
    <property name="Shuffle" type="b" access="readwrite"/>
    <property name="Metadata" type="a{sv}" access="read"/>
    <property name="Volume" type="d" access="readwrite"/>
    <property name="Position" type="x" access="read"/>
    <property name="MinimumRate" type="d" access="read"/>
    <property name="MaximumRate" type="d" access="read"/>
    <property name="CanGoNext" type="b" access="read"/>
    <property name="CanGoPrevious" type="b" access="read"/>
    <property name="CanPlay" type="b" access="read"/>
    <property name="CanPause" type="b" access="read"/>
    <property name="CanSeek" type="b" access="read"/>
    <property name="CanControl" type="b" access="read"/>
  </interface>
</node>
)XML";

enum DirtyMask : uint32_t {
    DIRTY_NONE = 0,
    DIRTY_PLAYBACK_STATUS = 1u << 0,
    DIRTY_METADATA        = 1u << 1,
    DIRTY_LOOP_STATUS     = 1u << 2,
    DIRTY_SHUFFLE         = 1u << 3,
    DIRTY_VOLUME          = 1u << 4,
    DIRTY_CAPS            = 1u << 5,
    DIRTY_SEEKED          = 1u << 6,
};

std::string buildTrackObjectPath(const MprisTrackState& st) {
    if (!st.hasTrack || st.trackId.empty()) {
        return NO_TRACK_OBJECT_PATH;
    }
    std::string sanitized;
    sanitized.reserve(st.trackId.size() + 8);
    sanitized.append("t_");
    for (unsigned char c : st.trackId) {
        if (std::isalnum(c) || c == '_') {
            sanitized.push_back(static_cast<char>(c));
        } else {
            char hex[4];
            std::snprintf(hex, sizeof(hex), "%02x", c);
            sanitized.push_back('_');
            sanitized.append(hex);
        }
    }
    return std::string("/com/zuna/player/track/") + sanitized;
}

GVariant* buildMetadataVariant(const MprisTrackState& st, const std::string& objPath) {
    GVariantBuilder builder;
    g_variant_builder_init(&builder, G_VARIANT_TYPE("a{sv}"));

    if (!st.hasTrack) {
        g_variant_builder_add(&builder, "{sv}", "mpris:trackid", g_variant_new_object_path(NO_TRACK_OBJECT_PATH));
        return g_variant_builder_end(&builder);
    }

    g_variant_builder_add(&builder, "{sv}", "mpris:trackid", g_variant_new_object_path(objPath.c_str()));
    g_variant_builder_add(&builder, "{sv}", "mpris:length", g_variant_new_int64(std::max<int64_t>(0, st.durationUs)));

    if (!st.artUrl.empty()) {
        g_variant_builder_add(&builder, "{sv}", "mpris:artUrl", g_variant_new_string(st.artUrl.c_str()));
    }
    if (!st.trackUrl.empty()) {
        g_variant_builder_add(&builder, "{sv}", "xesam:url", g_variant_new_string(st.trackUrl.c_str()));
    }

    const char* title = st.title.empty() ? "Unknown Track" : st.title.c_str();
    g_variant_builder_add(&builder, "{sv}", "xesam:title", g_variant_new_string(title));

    GVariantBuilder artistBuilder;
    g_variant_builder_init(&artistBuilder, G_VARIANT_TYPE("as"));
    const char* artist = st.artist.empty() ? "Unknown Artist" : st.artist.c_str();
    g_variant_builder_add(&artistBuilder, "s", artist);
    g_variant_builder_add(&builder, "{sv}", "xesam:artist", g_variant_builder_end(&artistBuilder));

    if (!st.album.empty()) {
        g_variant_builder_add(&builder, "{sv}", "xesam:album", g_variant_new_string(st.album.c_str()));
    }

    return g_variant_builder_end(&builder);
}

} // namespace

struct LinuxMprisService::Impl {
    std::mutex mutex;
    ActionCallback callback;

    MprisTrackState state;
    std::string trackObjectPath = NO_TRACK_OBJECT_PATH;
    std::chrono::steady_clock::time_point positionAnchorTime = std::chrono::steady_clock::now();
    uint32_t dirtyFlags = DIRTY_NONE;
    bool flushScheduled = false;

    std::thread thread;
    GMainContext* mainContext = nullptr;
    GMainLoop* mainLoop = nullptr;
    GDBusNodeInfo* introspection = nullptr;
    GDBusConnection* connection = nullptr;
    guint rootRegId = 0;
    guint playerRegId = 0;
    guint ownerId = 0;
    bool fallbackRequested = false;

    int64_t currentPositionUsLocked() const {
        int64_t pos = std::max<int64_t>(0, state.positionUs);
        if (state.hasTrack && state.playbackStatus == "Playing") {
            auto elapsedUs = std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - positionAnchorTime
            ).count();
            if (elapsedUs > 0) pos += elapsedUs;
        }
        if (state.durationUs > 0 && pos > state.durationUs) {
            pos = state.durationUs;
        }
        return pos;
    }

    void dispatchAction(const MprisActionEvent& ev) {
        ActionCallback cbCopy;
        {
            std::lock_guard<std::mutex> lock(mutex);
            cbCopy = callback;
        }
        if (cbCopy) cbCopy(ev);
    }

    void flushSignalsOnLoopThread() {
        GDBusConnection* conn = nullptr;
        uint32_t flags = DIRTY_NONE;
        MprisTrackState snapshot;
        std::string objPath;
        int64_t seekPosUs = 0;

        {
            std::lock_guard<std::mutex> lock(mutex);
            flushScheduled = false;
            conn = connection ? static_cast<GDBusConnection*>(g_object_ref(connection)) : nullptr;
            flags = dirtyFlags;
            dirtyFlags = DIRTY_NONE;
            snapshot = state;
            objPath = trackObjectPath;
            seekPosUs = snapshot.positionUs;
        }

        if (!conn || flags == DIRTY_NONE) {
            if (conn) g_object_unref(conn);
            return;
        }

        if ((flags & ~DIRTY_SEEKED) != DIRTY_NONE) {
            GVariantBuilder changed;
            g_variant_builder_init(&changed, G_VARIANT_TYPE("a{sv}"));

            if (flags & DIRTY_PLAYBACK_STATUS) {
                g_variant_builder_add(&changed, "{sv}", "PlaybackStatus", g_variant_new_string(snapshot.playbackStatus.c_str()));
            }
            if (flags & DIRTY_METADATA) {
                g_variant_builder_add(&changed, "{sv}", "Metadata", buildMetadataVariant(snapshot, objPath));
            }
            if (flags & DIRTY_LOOP_STATUS) {
                g_variant_builder_add(&changed, "{sv}", "LoopStatus", g_variant_new_string(snapshot.loopStatus.c_str()));
            }
            if (flags & DIRTY_SHUFFLE) {
                g_variant_builder_add(&changed, "{sv}", "Shuffle", g_variant_new_boolean(snapshot.shuffle ? TRUE : FALSE));
            }
            if (flags & DIRTY_VOLUME) {
                double vol = std::clamp(snapshot.volume, 0.0, 1.0);
                g_variant_builder_add(&changed, "{sv}", "Volume", g_variant_new_double(vol));
            }
            if (flags & DIRTY_CAPS) {
                bool canPlay = snapshot.hasTrack;
                bool canSeek = snapshot.hasTrack && snapshot.durationUs > 0;
                g_variant_builder_add(&changed, "{sv}", "CanGoNext", g_variant_new_boolean(canPlay ? TRUE : FALSE));
                g_variant_builder_add(&changed, "{sv}", "CanGoPrevious", g_variant_new_boolean(canPlay ? TRUE : FALSE));
                g_variant_builder_add(&changed, "{sv}", "CanPlay", g_variant_new_boolean(canPlay ? TRUE : FALSE));
                g_variant_builder_add(&changed, "{sv}", "CanPause", g_variant_new_boolean(canPlay ? TRUE : FALSE));
                g_variant_builder_add(&changed, "{sv}", "CanSeek", g_variant_new_boolean(canSeek ? TRUE : FALSE));
            }

            GVariantBuilder invalidated;
            g_variant_builder_init(&invalidated, G_VARIANT_TYPE("as"));

            GVariant* signalParams = g_variant_new(
                "(sa{sv}as)",
                MPRIS_PLAYER_IFACE,
                &changed,
                &invalidated
            );
            g_dbus_connection_emit_signal(
                conn,
                nullptr,
                MPRIS_OBJECT_PATH,
                "org.freedesktop.DBus.Properties",
                "PropertiesChanged",
                signalParams,
                nullptr
            );
        }

        if (flags & DIRTY_SEEKED) {
            g_dbus_connection_emit_signal(
                conn,
                nullptr,
                MPRIS_OBJECT_PATH,
                MPRIS_PLAYER_IFACE,
                "Seeked",
                g_variant_new("(x)", std::max<int64_t>(0, seekPosUs)),
                nullptr
            );
        }

        g_object_unref(conn);
    }

    static gboolean onFlushInvoke(gpointer userData) {
        auto* impl = static_cast<Impl*>(userData);
        impl->flushSignalsOnLoopThread();
        return G_SOURCE_REMOVE;
    }

    void scheduleFlushLocked() {
        if (!mainContext || flushScheduled || dirtyFlags == DIRTY_NONE) return;
        flushScheduled = true;
        GSource* src = g_idle_source_new();
        g_source_set_callback(src, &Impl::onFlushInvoke, this, nullptr);
        g_source_attach(src, mainContext);
        g_source_unref(src);
    }

    static void handleRootMethodCall(
        GDBusConnection* /*connection*/,
        const gchar* /*sender*/,
        const gchar* /*object_path*/,
        const gchar* /*interface_name*/,
        const gchar* method_name,
        GVariant* /*parameters*/,
        GDBusMethodInvocation* invocation,
        gpointer user_data
    ) {
        auto* impl = static_cast<Impl*>(user_data);
        if (std::strcmp(method_name, "Raise") == 0) {
            impl->dispatchAction(MprisActionEvent{"raise"});
        } else if (std::strcmp(method_name, "Quit") == 0) {
            impl->dispatchAction(MprisActionEvent{"quit"});
        }
        g_dbus_method_invocation_return_value(invocation, nullptr);
    }

    static GVariant* handleRootGetProperty(
        GDBusConnection* /*connection*/,
        const gchar* /*sender*/,
        const gchar* /*object_path*/,
        const gchar* /*interface_name*/,
        const gchar* property_name,
        GError** error,
        gpointer /*user_data*/
    ) {
        if (std::strcmp(property_name, "CanQuit") == 0) return g_variant_new_boolean(TRUE);
        if (std::strcmp(property_name, "CanRaise") == 0) return g_variant_new_boolean(TRUE);
        if (std::strcmp(property_name, "HasTrackList") == 0) return g_variant_new_boolean(FALSE);
        if (std::strcmp(property_name, "Identity") == 0) return g_variant_new_string("Zuna");
        if (std::strcmp(property_name, "DesktopEntry") == 0) return g_variant_new_string("zuna");
        if (std::strcmp(property_name, "SupportedUriSchemes") == 0) {
            const gchar* schemes[] = {"file", nullptr};
            return g_variant_new_strv(schemes, -1);
        }
        if (std::strcmp(property_name, "SupportedMimeTypes") == 0) {
            const gchar* mimes[] = {
                "audio/flac",
                "audio/wav",
                "audio/mpeg",
                "audio/ogg",
                "audio/mp4",
                "audio/aac",
                nullptr
            };
            return g_variant_new_strv(mimes, -1);
        }
        g_set_error(error, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_PROPERTY, "Unknown property %s", property_name);
        return nullptr;
    }

    static void handlePlayerMethodCall(
        GDBusConnection* /*connection*/,
        const gchar* /*sender*/,
        const gchar* /*object_path*/,
        const gchar* /*interface_name*/,
        const gchar* method_name,
        GVariant* parameters,
        GDBusMethodInvocation* invocation,
        gpointer user_data
    ) {
        auto* impl = static_cast<Impl*>(user_data);

        if (std::strcmp(method_name, "Next") == 0) {
            impl->dispatchAction(MprisActionEvent{"next"});
        } else if (std::strcmp(method_name, "Previous") == 0) {
            impl->dispatchAction(MprisActionEvent{"prev"});
        } else if (std::strcmp(method_name, "Pause") == 0) {
            impl->dispatchAction(MprisActionEvent{"pause"});
        } else if (std::strcmp(method_name, "PlayPause") == 0) {
            impl->dispatchAction(MprisActionEvent{"toggle"});
        } else if (std::strcmp(method_name, "Stop") == 0) {
            impl->dispatchAction(MprisActionEvent{"stop"});
        } else if (std::strcmp(method_name, "Play") == 0) {
            impl->dispatchAction(MprisActionEvent{"play"});
        } else if (std::strcmp(method_name, "Seek") == 0) {
            gint64 offsetUs = 0;
            g_variant_get(parameters, "(x)", &offsetUs);
            MprisActionEvent ev;
            ev.type = "seekRelative";
            ev.numberValue = static_cast<double>(offsetUs) / 1000000.0;
            impl->dispatchAction(ev);
        } else if (std::strcmp(method_name, "SetPosition") == 0) {
            const gchar* trackObjPath = nullptr;
            gint64 posUs = 0;
            g_variant_get(parameters, "(&ox)", &trackObjPath, &posUs);
            bool matchesTrack = false;
            {
                std::lock_guard<std::mutex> lock(impl->mutex);
                matchesTrack = impl->state.hasTrack && trackObjPath &&
                               impl->trackObjectPath == trackObjPath;
            }
            if (matchesTrack) {
                MprisActionEvent ev;
                ev.type = "seekAbsolute";
                ev.numberValue = std::max(0.0, static_cast<double>(posUs) / 1000000.0);
                impl->dispatchAction(ev);
            }
        }
        g_dbus_method_invocation_return_value(invocation, nullptr);
    }

    static GVariant* handlePlayerGetProperty(
        GDBusConnection* /*connection*/,
        const gchar* /*sender*/,
        const gchar* /*object_path*/,
        const gchar* /*interface_name*/,
        const gchar* property_name,
        GError** error,
        gpointer user_data
    ) {
        auto* impl = static_cast<Impl*>(user_data);
        std::lock_guard<std::mutex> lock(impl->mutex);

        if (std::strcmp(property_name, "PlaybackStatus") == 0) {
            return g_variant_new_string(impl->state.playbackStatus.c_str());
        }
        if (std::strcmp(property_name, "LoopStatus") == 0) {
            return g_variant_new_string(impl->state.loopStatus.c_str());
        }
        if (std::strcmp(property_name, "Rate") == 0) {
            return g_variant_new_double(1.0);
        }
        if (std::strcmp(property_name, "MinimumRate") == 0) {
            return g_variant_new_double(1.0);
        }
        if (std::strcmp(property_name, "MaximumRate") == 0) {
            return g_variant_new_double(1.0);
        }
        if (std::strcmp(property_name, "Shuffle") == 0) {
            return g_variant_new_boolean(impl->state.shuffle ? TRUE : FALSE);
        }
        if (std::strcmp(property_name, "Metadata") == 0) {
            return buildMetadataVariant(impl->state, impl->trackObjectPath);
        }
        if (std::strcmp(property_name, "Volume") == 0) {
            return g_variant_new_double(std::clamp(impl->state.volume, 0.0, 1.0));
        }
        if (std::strcmp(property_name, "Position") == 0) {
            return g_variant_new_int64(impl->currentPositionUsLocked());
        }
        if (std::strcmp(property_name, "CanGoNext") == 0 ||
            std::strcmp(property_name, "CanGoPrevious") == 0 ||
            std::strcmp(property_name, "CanPlay") == 0 ||
            std::strcmp(property_name, "CanPause") == 0) {
            return g_variant_new_boolean(impl->state.hasTrack ? TRUE : FALSE);
        }
        if (std::strcmp(property_name, "CanSeek") == 0) {
            bool canSeek = impl->state.hasTrack && impl->state.durationUs > 0;
            return g_variant_new_boolean(canSeek ? TRUE : FALSE);
        }
        if (std::strcmp(property_name, "CanControl") == 0) {
            return g_variant_new_boolean(TRUE);
        }

        g_set_error(error, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_PROPERTY, "Unknown property %s", property_name);
        return nullptr;
    }

    static gboolean handlePlayerSetProperty(
        GDBusConnection* /*connection*/,
        const gchar* /*sender*/,
        const gchar* /*object_path*/,
        const gchar* /*interface_name*/,
        const gchar* property_name,
        GVariant* value,
        GError** error,
        gpointer user_data
    ) {
        auto* impl = static_cast<Impl*>(user_data);

        if (std::strcmp(property_name, "Volume") == 0) {
            double vol = std::clamp(g_variant_get_double(value), 0.0, 1.0);
            MprisActionEvent ev;
            ev.type = "setVolume";
            ev.numberValue = vol;
            impl->dispatchAction(ev);
            return TRUE;
        }
        if (std::strcmp(property_name, "LoopStatus") == 0) {
            const gchar* status = g_variant_get_string(value, nullptr);
            if (!status ||
                (std::strcmp(status, "None") != 0 &&
                 std::strcmp(status, "Track") != 0 &&
                 std::strcmp(status, "Playlist") != 0)) {
                g_set_error(error, G_DBUS_ERROR, G_DBUS_ERROR_INVALID_ARGS, "Invalid LoopStatus");
                return FALSE;
            }
            MprisActionEvent ev;
            ev.type = "setLoopStatus";
            ev.stringValue = status;
            impl->dispatchAction(ev);
            return TRUE;
        }
        if (std::strcmp(property_name, "Shuffle") == 0) {
            bool shuf = g_variant_get_boolean(value) != FALSE;
            MprisActionEvent ev;
            ev.type = "setShuffle";
            ev.boolValue = shuf;
            impl->dispatchAction(ev);
            return TRUE;
        }
        if (std::strcmp(property_name, "Rate") == 0) {
            return TRUE;
        }

        g_set_error(error, G_DBUS_ERROR, G_DBUS_ERROR_PROPERTY_READ_ONLY, "Property %s is not writable", property_name);
        return FALSE;
    }

    static void onBusAcquired(GDBusConnection* conn, const gchar* /*name*/, gpointer user_data) {
        auto* impl = static_cast<Impl*>(user_data);
        if (!impl || !impl->introspection) return;

        static const GDBusInterfaceVTable rootVTable = {
            &Impl::handleRootMethodCall,
            &Impl::handleRootGetProperty,
            nullptr,
            { nullptr }
        };
        static const GDBusInterfaceVTable playerVTable = {
            &Impl::handlePlayerMethodCall,
            &Impl::handlePlayerGetProperty,
            &Impl::handlePlayerSetProperty,
            { nullptr }
        };

        GDBusInterfaceInfo* rootIface = g_dbus_node_info_lookup_interface(impl->introspection, MPRIS_ROOT_IFACE);
        GDBusInterfaceInfo* playerIface = g_dbus_node_info_lookup_interface(impl->introspection, MPRIS_PLAYER_IFACE);

        guint rId = 0;
        guint pId = 0;
        if (rootIface) {
            rId = g_dbus_connection_register_object(
                conn,
                MPRIS_OBJECT_PATH,
                rootIface,
                &rootVTable,
                impl,
                nullptr,
                nullptr
            );
        }
        if (playerIface) {
            pId = g_dbus_connection_register_object(
                conn,
                MPRIS_OBJECT_PATH,
                playerIface,
                &playerVTable,
                impl,
                nullptr,
                nullptr
            );
        }

        std::lock_guard<std::mutex> lock(impl->mutex);
        impl->connection = static_cast<GDBusConnection*>(g_object_ref(conn));
        impl->rootRegId = rId;
        impl->playerRegId = pId;
    }

    static void onNameAcquired(GDBusConnection* /*conn*/, const gchar* /*name*/, gpointer user_data) {
        auto* impl = static_cast<Impl*>(user_data);
        std::lock_guard<std::mutex> lock(impl->mutex);
        if (impl->dirtyFlags != DIRTY_NONE) {
            impl->scheduleFlushLocked();
        }
    }

    static void onNameLost(GDBusConnection* conn, const gchar* name, gpointer user_data) {
        auto* impl = static_cast<Impl*>(user_data);
        if (!impl) return;
        // If primary bus name was lost because another instance already owns it, fallback to .instance<pid>
        if (conn && name && std::strcmp(name, MPRIS_PRIMARY_BUS) == 0 && !impl->fallbackRequested) {
            impl->fallbackRequested = true;
            char fallbackName[128];
            std::snprintf(fallbackName, sizeof(fallbackName), "%s.instance%d", MPRIS_PRIMARY_BUS, static_cast<int>(::getpid()));
            if (impl->ownerId != 0) {
                g_bus_unown_name(impl->ownerId);
                impl->ownerId = 0;
            }
            impl->ownerId = g_bus_own_name_on_connection(
                conn,
                fallbackName,
                G_BUS_NAME_OWNER_FLAGS_NONE,
                &Impl::onNameAcquired,
                nullptr,
                impl,
                nullptr
            );
        }
    }
};

LinuxMprisService::LinuxMprisService() = default;

LinuxMprisService::~LinuxMprisService() {
    stop();
}

bool LinuxMprisService::start(ActionCallback cb) {
    if (mRunning.load(std::memory_order_acquire)) {
        return true;
    }

    GError* err = nullptr;
    GDBusNodeInfo* info = g_dbus_node_info_new_for_xml(MPRIS_INTROSPECTION_XML, &err);
    if (!info) {
        if (err) g_error_free(err);
        return false;
    }

    auto* impl = new Impl();
    impl->callback = std::move(cb);
    impl->introspection = info;
    mImpl = impl;
    mStopRequested.store(false, std::memory_order_release);
    mRunning.store(true, std::memory_order_release);

    std::mutex initMutex;
    std::condition_variable initCv;
    bool contextReady = false;

    impl->thread = std::thread([this, impl, &initMutex, &initCv, &contextReady]() {
        GMainContext* ctx = g_main_context_new();
        g_main_context_push_thread_default(ctx);
        GMainLoop* loop = g_main_loop_new(ctx, FALSE);

        {
            std::lock_guard<std::mutex> lock(impl->mutex);
            impl->mainContext = ctx;
            impl->mainLoop = loop;
        }

        guint ownId = g_bus_own_name(
            G_BUS_TYPE_SESSION,
            MPRIS_PRIMARY_BUS,
            G_BUS_NAME_OWNER_FLAGS_NONE,
            &Impl::onBusAcquired,
            &Impl::onNameAcquired,
            &Impl::onNameLost,
            impl,
            nullptr
        );

        {
            std::lock_guard<std::mutex> lock(impl->mutex);
            impl->ownerId = ownId;
        }

        {
            std::lock_guard<std::mutex> lk(initMutex);
            contextReady = true;
        }
        initCv.notify_one();

        g_main_loop_run(loop);

        {
            std::lock_guard<std::mutex> lock(impl->mutex);
            if (impl->ownerId != 0) {
                g_bus_unown_name(impl->ownerId);
                impl->ownerId = 0;
            }
            if (impl->connection) {
                if (impl->rootRegId != 0) {
                    g_dbus_connection_unregister_object(impl->connection, impl->rootRegId);
                    impl->rootRegId = 0;
                }
                if (impl->playerRegId != 0) {
                    g_dbus_connection_unregister_object(impl->connection, impl->playerRegId);
                    impl->playerRegId = 0;
                }
                g_object_unref(impl->connection);
                impl->connection = nullptr;
            }
            impl->mainLoop = nullptr;
            impl->mainContext = nullptr;
        }

        g_main_loop_unref(loop);
        g_main_context_pop_thread_default(ctx);
        g_main_context_unref(ctx);
    });

    std::unique_lock<std::mutex> lk(initMutex);
    initCv.wait(lk, [&]() { return contextReady; });
    return true;
}

void LinuxMprisService::stop() {
    if (!mImpl) {
        mRunning.store(false, std::memory_order_release);
        return;
    }

    mStopRequested.store(true, std::memory_order_release);
    Impl* impl = mImpl;

    {
        std::lock_guard<std::mutex> lock(impl->mutex);
        impl->callback = nullptr;
        if (impl->mainLoop && impl->mainContext) {
            GMainLoop* loop = impl->mainLoop;
            g_main_context_invoke_full(
                impl->mainContext,
                G_PRIORITY_HIGH,
                [](gpointer data) -> gboolean {
                    g_main_loop_quit(static_cast<GMainLoop*>(data));
                    return G_SOURCE_REMOVE;
                },
                g_main_loop_ref(loop),
                [](gpointer data) {
                    g_main_loop_unref(static_cast<GMainLoop*>(data));
                }
            );
        }
    }

    if (impl->thread.joinable()) {
        impl->thread.join();
    }

    if (impl->introspection) {
        g_dbus_node_info_unref(impl->introspection);
        impl->introspection = nullptr;
    }

    delete impl;
    mImpl = nullptr;
    mRunning.store(false, std::memory_order_release);
}

void LinuxMprisService::updateState(const MprisTrackState& nextState) {
    if (!mImpl || mStopRequested.load(std::memory_order_acquire)) return;

    Impl* impl = mImpl;
    std::lock_guard<std::mutex> lock(impl->mutex);

    uint32_t mask = DIRTY_NONE;
    if (impl->state.playbackStatus != nextState.playbackStatus) mask |= DIRTY_PLAYBACK_STATUS;
    if (impl->state.loopStatus != nextState.loopStatus) mask |= DIRTY_LOOP_STATUS;
    if (impl->state.shuffle != nextState.shuffle) mask |= DIRTY_SHUFFLE;
    if (std::abs(impl->state.volume - nextState.volume) > 0.002) mask |= DIRTY_VOLUME;
    if (impl->state.hasTrack != nextState.hasTrack ||
        (impl->state.durationUs > 0) != (nextState.durationUs > 0)) {
        mask |= DIRTY_CAPS;
    }
    if (impl->state.hasTrack != nextState.hasTrack ||
        impl->state.trackId != nextState.trackId ||
        impl->state.title != nextState.title ||
        impl->state.artist != nextState.artist ||
        impl->state.album != nextState.album ||
        impl->state.artUrl != nextState.artUrl ||
        impl->state.trackUrl != nextState.trackUrl ||
        impl->state.durationUs != nextState.durationUs) {
        mask |= DIRTY_METADATA;
    }
    if (nextState.seeked) {
        mask |= DIRTY_SEEKED;
    }

    impl->state = nextState;
    impl->trackObjectPath = buildTrackObjectPath(nextState);
    impl->positionAnchorTime = std::chrono::steady_clock::now();

    if (mask != DIRTY_NONE) {
        impl->dirtyFlags |= mask;
        if (impl->connection) {
            impl->scheduleFlushLocked();
        }
    }
}

#else

LinuxMprisService::LinuxMprisService() = default;
LinuxMprisService::~LinuxMprisService() = default;
bool LinuxMprisService::start(ActionCallback) { return false; }
void LinuxMprisService::stop() {}
void LinuxMprisService::updateState(const MprisTrackState&) {}

#endif
