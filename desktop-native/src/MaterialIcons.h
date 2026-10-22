#pragma once

#include <d2d1.h>
#include <cmath>

class MaterialIcons {
public:
    // Play Arrow (Material Rounded)
    static void drawPlay(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        ID2D1Factory* factory = nullptr;
        rt->GetFactory(&factory);
        if (!factory) return;

        ID2D1PathGeometry* path = nullptr;
        if (SUCCEEDED(factory->CreatePathGeometry(&path))) {
            ID2D1GeometrySink* sink = nullptr;
            if (SUCCEEDED(path->Open(&sink))) {
                float pad = size * 0.15f;
                float left = x + pad;
                float top = y + pad;
                float right = x + size - pad * 0.5f;
                float bottom = y + size - pad;
                float midY = y + size * 0.5f;

                sink->BeginFigure(D2D1::Point2F(left, top), D2D1_FIGURE_BEGIN_FILLED);
                sink->AddLine(D2D1::Point2F(right, midY));
                sink->AddLine(D2D1::Point2F(left, bottom));
                sink->EndFigure(D2D1_FIGURE_END_CLOSED);
                sink->Close();
                sink->Release();

                rt->FillGeometry(path, brush);
            }
            path->Release();
        }
    }

    // Pause (Material Rounded)
    static void drawPause(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        float barW = size * 0.24f;
        float gap = size * 0.22f;
        float barH = size * 0.7f;
        float topY = y + (size - barH) * 0.5f;
        float left1 = x + (size - (2 * barW + gap)) * 0.5f;
        float left2 = left1 + barW + gap;

        rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(left1, topY, left1 + barW, topY + barH), 2.5f, 2.5f), brush);
        rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(left2, topY, left2 + barW, topY + barH), 2.5f, 2.5f), brush);
    }

    // Skip Next
    static void drawSkipNext(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        float h = size * 0.65f;
        float triW = size * 0.45f;
        float barW = size * 0.14f;
        float sy = y + (size - h) * 0.5f;
        float sx = x + (size - (triW + barW + 3.0f)) * 0.5f;

        drawPlay(rt, brush, sx - 2.0f, sy - 2.0f, h + 4.0f);
        float barX = sx + triW + 2.0f;
        rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(barX, sy, barX + barW, sy + h), 1.5f, 1.5f), brush);
    }

    // Skip Previous
    static void drawSkipPrevious(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        ID2D1Factory* factory = nullptr;
        rt->GetFactory(&factory);
        if (!factory) return;

        float h = size * 0.65f;
        float triW = size * 0.45f;
        float barW = size * 0.14f;
        float sy = y + (size - h) * 0.5f;
        float sx = x + (size - (triW + barW + 3.0f)) * 0.5f;

        rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(sx, sy, sx + barW, sy + h), 1.5f, 1.5f), brush);

        float triX = sx + barW + 2.0f;
        ID2D1PathGeometry* path = nullptr;
        if (SUCCEEDED(factory->CreatePathGeometry(&path))) {
            ID2D1GeometrySink* sink = nullptr;
            if (SUCCEEDED(path->Open(&sink))) {
                sink->BeginFigure(D2D1::Point2F(triX + triW, sy), D2D1_FIGURE_BEGIN_FILLED);
                sink->AddLine(D2D1::Point2F(triX, sy + h * 0.5f));
                sink->AddLine(D2D1::Point2F(triX + triW, sy + h));
                sink->EndFigure(D2D1_FIGURE_END_CLOSED);
                sink->Close();
                sink->Release();

                rt->FillGeometry(path, brush);
            }
            path->Release();
        }
    }

    // Shuffle (Material Rounded)
    static void drawShuffle(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        float pad = size * 0.2f;
        float w = size - 2 * pad;
        float h = size * 0.5f;
        float sx = x + pad;
        float sy = y + (size - h) * 0.5f;

        rt->DrawLine(D2D1::Point2F(sx, sy), D2D1::Point2F(sx + w * 0.4f, sy), brush, 2.0f);
        rt->DrawLine(D2D1::Point2F(sx + w * 0.4f, sy), D2D1::Point2F(sx + w * 0.7f, sy + h), brush, 2.0f);
        rt->DrawLine(D2D1::Point2F(sx + w * 0.7f, sy + h), D2D1::Point2F(sx + w, sy + h), brush, 2.0f);

        rt->DrawLine(D2D1::Point2F(sx, sy + h), D2D1::Point2F(sx + w * 0.4f, sy + h), brush, 2.0f);
        rt->DrawLine(D2D1::Point2F(sx + w * 0.4f, sy + h), D2D1::Point2F(sx + w * 0.7f, sy), brush, 2.0f);
        rt->DrawLine(D2D1::Point2F(sx + w * 0.7f, sy), D2D1::Point2F(sx + w, sy), brush, 2.0f);

        // Arrowheads
        rt->DrawLine(D2D1::Point2F(sx + w - 4.0f, sy - 3.0f), D2D1::Point2F(sx + w, sy), brush, 2.0f);
        rt->DrawLine(D2D1::Point2F(sx + w - 4.0f, sy + 3.0f), D2D1::Point2F(sx + w, sy), brush, 2.0f);

        rt->DrawLine(D2D1::Point2F(sx + w - 4.0f, sy + h - 3.0f), D2D1::Point2F(sx + w, sy + h), brush, 2.0f);
        rt->DrawLine(D2D1::Point2F(sx + w - 4.0f, sy + h + 3.0f), D2D1::Point2F(sx + w, sy + h), brush, 2.0f);
    }

    // Repeat (Material Rounded)
    static void drawRepeat(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        float r = size * 0.32f;
        float cx = x + size * 0.5f;
        float cy = y + size * 0.5f;

        rt->DrawEllipse(D2D1::Ellipse(D2D1::Point2F(cx, cy), r, r), brush, 2.0f);
        rt->FillRectangle(D2D1::RectF(cx, cy - r - 3.0f, cx + 6.0f, cy - r + 3.0f), brush);
    }

    // Volume Up / Speaker (Material Rounded)
    static void drawVolumeUp(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size, bool isMuted = false) {
        ID2D1Factory* factory = nullptr;
        rt->GetFactory(&factory);
        if (!factory) return;

        float sw = size * 0.35f;
        float sh = size * 0.45f;
        float sy = y + (size - sh) * 0.5f;
        float sx = x + 2.0f;

        // Speaker cone
        ID2D1PathGeometry* path = nullptr;
        if (SUCCEEDED(factory->CreatePathGeometry(&path))) {
            ID2D1GeometrySink* sink = nullptr;
            if (SUCCEEDED(path->Open(&sink))) {
                sink->BeginFigure(D2D1::Point2F(sx, sy + sh * 0.3f), D2D1_FIGURE_BEGIN_FILLED);
                sink->AddLine(D2D1::Point2F(sx + sw * 0.4f, sy + sh * 0.3f));
                sink->AddLine(D2D1::Point2F(sx + sw, sy));
                sink->AddLine(D2D1::Point2F(sx + sw, sy + sh));
                sink->AddLine(D2D1::Point2F(sx + sw * 0.4f, sy + sh * 0.7f));
                sink->AddLine(D2D1::Point2F(sx, sy + sh * 0.7f));
                sink->EndFigure(D2D1_FIGURE_END_CLOSED);
                sink->Close();
                sink->Release();

                rt->FillGeometry(path, brush);
            }
            path->Release();
        }

        // Sound waves or Mute slash
        if (isMuted) {
            float crossX = sx + sw + size * 0.15f;
            float crossY = y + size * 0.3f;
            float crossS = size * 0.4f;
            rt->DrawLine(D2D1::Point2F(crossX, crossY), D2D1::Point2F(crossX + crossS, crossY + crossS), brush, 2.0f);
            rt->DrawLine(D2D1::Point2F(crossX + crossS, crossY), D2D1::Point2F(crossX, crossY + crossS), brush, 2.0f);
        } else {
            float arcX = sx + sw + size * 0.1f;
            rt->DrawLine(D2D1::Point2F(arcX, y + size * 0.36f), D2D1::Point2F(arcX + size * 0.12f, y + size * 0.5f), brush, 2.0f);
            rt->DrawLine(D2D1::Point2F(arcX + size * 0.12f, y + size * 0.5f), D2D1::Point2F(arcX, y + size * 0.64f), brush, 2.0f);

            float arc2X = arcX + size * 0.16f;
            rt->DrawLine(D2D1::Point2F(arc2X, y + size * 0.26f), D2D1::Point2F(arc2X + size * 0.16f, y + size * 0.5f), brush, 2.0f);
            rt->DrawLine(D2D1::Point2F(arc2X + size * 0.16f, y + size * 0.5f), D2D1::Point2F(arc2X, y + size * 0.74f), brush, 2.0f);
        }
    }

    // Favorite / Heart (Material Symbols)
    static void drawFavorite(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size, bool filled = true) {
        ID2D1Factory* factory = nullptr;
        rt->GetFactory(&factory);
        if (!factory) return;

        float cx = x + size * 0.5f;
        float cy = y + size * 0.35f;
        float r = size * 0.24f;

        ID2D1PathGeometry* path = nullptr;
        if (SUCCEEDED(factory->CreatePathGeometry(&path))) {
            ID2D1GeometrySink* sink = nullptr;
            if (SUCCEEDED(path->Open(&sink))) {
                sink->BeginFigure(D2D1::Point2F(cx, cy + r * 1.8f), filled ? D2D1_FIGURE_BEGIN_FILLED : D2D1_FIGURE_BEGIN_HOLLOW);
                sink->AddBezier(D2D1::BezierSegment(
                    D2D1::Point2F(cx - r * 1.5f, cy + r * 0.6f),
                    D2D1::Point2F(cx - r * 1.5f, cy - r * 0.5f),
                    D2D1::Point2F(cx - r * 0.7f, cy - r * 0.8f)
                ));
                sink->AddBezier(D2D1::BezierSegment(
                    D2D1::Point2F(cx - r * 0.2f, cy - r * 0.8f),
                    D2D1::Point2F(cx, cy - r * 0.2f),
                    D2D1::Point2F(cx, cy - r * 0.2f)
                ));
                sink->AddBezier(D2D1::BezierSegment(
                    D2D1::Point2F(cx, cy - r * 0.2f),
                    D2D1::Point2F(cx + r * 0.2f, cy - r * 0.8f),
                    D2D1::Point2F(cx + r * 0.7f, cy - r * 0.8f)
                ));
                sink->AddBezier(D2D1::BezierSegment(
                    D2D1::Point2F(cx + r * 1.5f, cy - r * 0.5f),
                    D2D1::Point2F(cx + r * 1.5f, cy + r * 0.6f),
                    D2D1::Point2F(cx, cy + r * 1.8f)
                ));
                sink->EndFigure(D2D1_FIGURE_END_CLOSED);
                sink->Close();
                sink->Release();

                if (filled) {
                    rt->FillGeometry(path, brush);
                } else {
                    rt->DrawGeometry(path, brush, 1.8f);
                }
            }
            path->Release();
        }
    }

    // Library Music (Note with Stack)
    static void drawLibraryMusic(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        float pad = size * 0.15f;
        float w = size - 2 * pad;
        float h = size - 2 * pad;
        float sx = x + pad;
        float sy = y + pad;

        rt->DrawRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(sx, sy, sx + w, sy + h), 3.0f, 3.0f), brush, 1.8f);
        float stemX = sx + w * 0.58f;
        float noteY = sy + h * 0.65f;
        rt->FillEllipse(D2D1::Ellipse(D2D1::Point2F(stemX - 4.0f, noteY), 3.5f, 2.5f), brush);
        rt->DrawLine(D2D1::Point2F(stemX, noteY), D2D1::Point2F(stemX, sy + h * 0.25f), brush, 2.0f);
        rt->DrawLine(D2D1::Point2F(stemX, sy + h * 0.25f), D2D1::Point2F(stemX + 5.0f, sy + h * 0.32f), brush, 2.0f);
    }

    // Music Note
    static void drawMusicNote(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        float stemH = size * 0.7f;
        float noteR = size * 0.2f;

        rt->FillEllipse(D2D1::Ellipse(D2D1::Point2F(x + noteR + 2.0f, y + stemH), noteR, noteR * 0.75f), brush);
        rt->DrawLine(D2D1::Point2F(x + noteR * 2.0f, y + stemH), D2D1::Point2F(x + noteR * 2.0f, y + 2.0f), brush, 2.0f);
        rt->DrawLine(D2D1::Point2F(x + noteR * 2.0f, y + 2.0f), D2D1::Point2F(x + size - 2.0f, y + size * 0.3f), brush, 2.5f);
    }

    // Queue Music / Playlists
    static void drawQueueMusic(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        float pad = size * 0.18f;
        float sx = x + pad;
        float sy = y + pad;
        float w = size - 2 * pad;

        rt->DrawLine(D2D1::Point2F(sx, sy + 3.0f), D2D1::Point2F(sx + w * 0.7f, sy + 3.0f), brush, 2.0f);
        rt->DrawLine(D2D1::Point2F(sx, sy + 9.0f), D2D1::Point2F(sx + w * 0.7f, sy + 9.0f), brush, 2.0f);
        rt->DrawLine(D2D1::Point2F(sx, sy + 15.0f), D2D1::Point2F(sx + w * 0.45f, sy + 15.0f), brush, 2.0f);

        float noteX = sx + w * 0.82f;
        rt->FillEllipse(D2D1::Ellipse(D2D1::Point2F(noteX - 3.0f, sy + 14.0f), 3.0f, 2.2f), brush);
        rt->DrawLine(D2D1::Point2F(noteX, sy + 14.0f), D2D1::Point2F(noteX, sy + 5.0f), brush, 1.8f);
    }

    // Album (Vinyl Record Disc)
    static void drawAlbum(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        float r = size * 0.38f;
        float cx = x + size * 0.5f;
        float cy = y + size * 0.5f;

        rt->DrawEllipse(D2D1::Ellipse(D2D1::Point2F(cx, cy), r, r), brush, 1.8f);
        rt->DrawEllipse(D2D1::Ellipse(D2D1::Point2F(cx, cy), r * 0.65f, r * 0.65f), brush, 1.0f);
        rt->FillEllipse(D2D1::Ellipse(D2D1::Point2F(cx, cy), r * 0.25f, r * 0.25f), brush);
    }

    // Person / Artist
    static void drawPerson(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        float cx = x + size * 0.5f;
        float headR = size * 0.2f;
        float headY = y + size * 0.3f;

        rt->FillEllipse(D2D1::Ellipse(D2D1::Point2F(cx, headY), headR, headR), brush);

        float bodyW = size * 0.55f;
        float bodyH = size * 0.3f;
        float bodyY = y + size * 0.6f;
        rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(cx - bodyW * 0.5f, bodyY, cx + bodyW * 0.5f, bodyY + bodyH), 6.0f, 6.0f), brush);
    }

    // Settings / Gear (Material Symbols)
    static void drawSettings(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        float r = size * 0.35f;
        float cx = x + size * 0.5f;
        float cy = y + size * 0.5f;

        rt->DrawEllipse(D2D1::Ellipse(D2D1::Point2F(cx, cy), r, r), brush, 3.0f);
        rt->DrawEllipse(D2D1::Ellipse(D2D1::Point2F(cx, cy), r * 0.45f, r * 0.45f), brush, 2.0f);

        for (int i = 0; i < 8; ++i) {
            float angle = i * (3.14159265f / 4.0f);
            float tx = cx + std::cos(angle) * (r + 2.0f);
            float ty = cy + std::sin(angle) * (r + 2.0f);
            rt->FillEllipse(D2D1::Ellipse(D2D1::Point2F(tx, ty), 2.2f, 2.2f), brush);
        }
    }

    // Folder
    static void drawFolder(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        float w = size * 0.85f;
        float h = size * 0.65f;
        float tabW = w * 0.45f;
        float tabH = h * 0.3f;
        float sx = x + (size - w) * 0.5f;
        float sy = y + (size - h) * 0.5f;

        rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(sx, sy, sx + tabW, sy + tabH + 2.0f), 2.0f, 2.0f), brush);
        rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(sx, sy + tabH, sx + w, sy + h), 3.0f, 3.0f), brush);
    }

    // Visibility Off (Hidden Eye)
    static void drawVisibilityOff(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        float r = size * 0.35f;
        float cx = x + size * 0.5f;
        float cy = y + size * 0.5f;

        rt->DrawEllipse(D2D1::Ellipse(D2D1::Point2F(cx, cy), r, r * 0.65f), brush, 1.8f);
        rt->FillEllipse(D2D1::Ellipse(D2D1::Point2F(cx, cy), r * 0.35f, r * 0.35f), brush);
        rt->DrawLine(D2D1::Point2F(x + 3.0f, y + size - 3.0f), D2D1::Point2F(x + size - 3.0f, y + 3.0f), brush, 2.0f);
    }

    // Add / Plus
    static void drawAdd(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        float pad = size * 0.2f;
        float midX = x + size * 0.5f;
        float midY = y + size * 0.5f;
        rt->DrawLine(D2D1::Point2F(x + pad, midY), D2D1::Point2F(x + size - pad, midY), brush, 2.0f);
        rt->DrawLine(D2D1::Point2F(midX, y + pad), D2D1::Point2F(midX, y + size - pad), brush, 2.0f);
    }

    // More Vert (3 vertical dots)
    static void drawMoreVert(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        float cx = x + size * 0.5f;
        rt->FillEllipse(D2D1::Ellipse(D2D1::Point2F(cx, y + size * 0.25f), 1.8f, 1.8f), brush);
        rt->FillEllipse(D2D1::Ellipse(D2D1::Point2F(cx, y + size * 0.5f), 1.8f, 1.8f), brush);
        rt->FillEllipse(D2D1::Ellipse(D2D1::Point2F(cx, y + size * 0.75f), 1.8f, 1.8f), brush);
    }

    // Search (Magnifying Glass)
    static void drawSearch(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        float r = size * 0.32f;
        float cx = x + r + 2.0f;
        float cy = y + r + 2.0f;
        rt->DrawEllipse(D2D1::Ellipse(D2D1::Point2F(cx, cy), r, r), brush, 2.0f);
        float handleStart = r * 0.707f;
        rt->DrawLine(D2D1::Point2F(cx + handleStart, cy + handleStart), D2D1::Point2F(x + size - 1.0f, y + size - 1.0f), brush, 2.5f);
    }
};
