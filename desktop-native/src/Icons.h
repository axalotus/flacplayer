#pragma once
// Icons.h — Material Symbols Rounded, rendered via Direct2D path geometry
// All icons are 24px design grid, scaled to any size at runtime.

#include <d2d1.h>
#include <d2d1helper.h>
#include <cmath>

static constexpr float kPI = 3.14159265358979f;

class Icons {
public:
    enum class Id {
        Play, Pause,
        SkipNext, SkipPrev,
        Shuffle, Repeat, RepeatOne,
        VolumeUp, VolumeMute,
        Settings,
        FolderOpen, Refresh,
        LibraryMusic, Album, Person,
        Favorite, FavoriteOutline,
        VisibilityOff,
        Search, Add, MoreVert,
        QueueMusic, ChevronRight, ChevronLeft,
        Close, Check
    };

    // Draw icon centred in [x, y, x+size, y+size]
    static void draw(ID2D1RenderTarget* rt, ID2D1Brush* brush,
                     Id icon, float x, float y, float size, float strokeWidth = 2.0f) {
        switch (icon) {
            case Id::Play:           drawPlay(rt, brush, x, y, size); break;
            case Id::Pause:          drawPause(rt, brush, x, y, size); break;
            case Id::SkipNext:       drawSkipNext(rt, brush, x, y, size); break;
            case Id::SkipPrev:       drawSkipPrev(rt, brush, x, y, size); break;
            case Id::Shuffle:        drawShuffle(rt, brush, x, y, size, strokeWidth); break;
            case Id::Repeat:         drawRepeat(rt, brush, x, y, size, strokeWidth); break;
            case Id::RepeatOne:      drawRepeatOne(rt, brush, x, y, size, strokeWidth); break;
            case Id::VolumeUp:       drawVolume(rt, brush, x, y, size, false, strokeWidth); break;
            case Id::VolumeMute:     drawVolume(rt, brush, x, y, size, true, strokeWidth); break;
            case Id::Settings:       drawSettings(rt, brush, x, y, size, strokeWidth); break;
            case Id::FolderOpen:     drawFolder(rt, brush, x, y, size); break;
            case Id::Refresh:        drawRefresh(rt, brush, x, y, size, strokeWidth); break;
            case Id::LibraryMusic:   drawLibraryMusic(rt, brush, x, y, size, strokeWidth); break;
            case Id::Album:          drawAlbum(rt, brush, x, y, size, strokeWidth); break;
            case Id::Person:         drawPerson(rt, brush, x, y, size); break;
            case Id::Favorite:       drawHeart(rt, brush, x, y, size, true); break;
            case Id::FavoriteOutline:drawHeart(rt, brush, x, y, size, false, strokeWidth); break;
            case Id::VisibilityOff:  drawVisibilityOff(rt, brush, x, y, size, strokeWidth); break;
            case Id::Search:         drawSearch(rt, brush, x, y, size, strokeWidth); break;
            case Id::Add:            drawAdd(rt, brush, x, y, size, strokeWidth); break;
            case Id::MoreVert:       drawMoreVert(rt, brush, x, y, size); break;
            case Id::QueueMusic:     drawQueueMusic(rt, brush, x, y, size, strokeWidth); break;
            case Id::ChevronRight:   drawChevron(rt, brush, x, y, size, true, strokeWidth); break;
            case Id::ChevronLeft:    drawChevron(rt, brush, x, y, size, false, strokeWidth); break;
            case Id::Close:          drawClose(rt, brush, x, y, size, strokeWidth); break;
            case Id::Check:          drawCheck(rt, brush, x, y, size, strokeWidth); break;
        }
    }

private:
    // ── Helpers ──────────────────────────────────────────────────────────────
    static void fillTriangle(ID2D1RenderTarget* rt, ID2D1Brush* br,
                             D2D1_POINT_2F a, D2D1_POINT_2F b, D2D1_POINT_2F c) {
        ID2D1Factory* f = nullptr; rt->GetFactory(&f); if (!f) return;
        ID2D1PathGeometry* pg = nullptr;
        if (SUCCEEDED(f->CreatePathGeometry(&pg))) {
            ID2D1GeometrySink* s = nullptr;
            if (SUCCEEDED(pg->Open(&s))) {
                s->BeginFigure(a, D2D1_FIGURE_BEGIN_FILLED);
                s->AddLine(b); s->AddLine(c);
                s->EndFigure(D2D1_FIGURE_END_CLOSED);
                s->Close(); s->Release();
                rt->FillGeometry(pg, br);
            }
            pg->Release();
        }
        f->Release();
    }

    static void fillRoundedBar(ID2D1RenderTarget* rt, ID2D1Brush* br,
                                float x, float y, float w, float h, float r) {
        rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x, y, x+w, y+h), r, r), br);
    }

    // ── Play ─────────────────────────────────────────────────────────────────
    static void drawPlay(ID2D1RenderTarget* rt, ID2D1Brush* br, float x, float y, float s) {
        float pad = s * 0.18f;
        fillTriangle(rt, br,
            {x + pad,       y + pad},
            {x + s - pad*0.4f, y + s * 0.5f},
            {x + pad,       y + s - pad});
    }

    // ── Pause ────────────────────────────────────────────────────────────────
    static void drawPause(ID2D1RenderTarget* rt, ID2D1Brush* br, float x, float y, float s) {
        float bw = s * 0.22f, bh = s * 0.65f, r = bw * 0.4f;
        float ty = y + (s - bh) * 0.5f;
        float lx = x + (s - 2*bw - s*0.14f) * 0.5f;
        float rx = lx + bw + s * 0.14f;
        fillRoundedBar(rt, br, lx, ty, bw, bh, r);
        fillRoundedBar(rt, br, rx, ty, bw, bh, r);
    }

    // ── Skip Next ────────────────────────────────────────────────────────────
    static void drawSkipNext(ID2D1RenderTarget* rt, ID2D1Brush* br, float x, float y, float s) {
        float bw = s * 0.12f, th = s * 0.62f, r = bw * 0.4f;
        float ty = y + (s - th) * 0.5f;
        float tx = x + s * 0.1f;
        // triangle
        fillTriangle(rt, br, {tx, ty}, {tx + s*0.52f, ty + th*0.5f}, {tx, ty + th});
        // bar
        float barX = tx + s * 0.54f;
        fillRoundedBar(rt, br, barX, ty, bw, th, r);
    }

    // ── Skip Prev ────────────────────────────────────────────────────────────
    static void drawSkipPrev(ID2D1RenderTarget* rt, ID2D1Brush* br, float x, float y, float s) {
        float bw = s * 0.12f, th = s * 0.62f, r = bw * 0.4f;
        float ty = y + (s - th) * 0.5f;
        float barX = x + s * 0.1f;
        fillRoundedBar(rt, br, barX, ty, bw, th, r);
        float tx = barX + bw + s * 0.04f;
        fillTriangle(rt, br, {tx + s*0.52f, ty}, {tx, ty + th*0.5f}, {tx + s*0.52f, ty + th});
    }

    // ── Shuffle ──────────────────────────────────────────────────────────────
    static void drawShuffle(ID2D1RenderTarget* rt, ID2D1Brush* br,
                            float x, float y, float s, float sw) {
        float pad = s * 0.15f;
        float w = s - 2*pad, h = s * 0.38f;
        float sx = x + pad, sy1 = y + s*0.25f, sy2 = y + s*0.62f;
        // top line: sx→mid cross
        rt->DrawLine({sx, sy1}, {sx + w*0.35f, sy1}, br, sw);
        rt->DrawLine({sx + w*0.35f, sy1}, {sx + w*0.7f, sy2}, br, sw);
        rt->DrawLine({sx + w*0.7f, sy2}, {sx + w, sy2}, br, sw);
        // bottom line: sx→mid cross
        rt->DrawLine({sx, sy2}, {sx + w*0.35f, sy2}, br, sw);
        rt->DrawLine({sx + w*0.35f, sy2}, {sx + w*0.7f, sy1}, br, sw);
        rt->DrawLine({sx + w*0.7f, sy1}, {sx + w, sy1}, br, sw);
        // arrowheads
        float ax = sx + w, ad = s * 0.07f;
        rt->DrawLine({ax - ad, sy1 - ad}, {ax, sy1}, br, sw);
        rt->DrawLine({ax - ad, sy1 + ad}, {ax, sy1}, br, sw);
        rt->DrawLine({ax - ad, sy2 - ad}, {ax, sy2}, br, sw);
        rt->DrawLine({ax - ad, sy2 + ad}, {ax, sy2}, br, sw);
    }

    // ── Repeat ───────────────────────────────────────────────────────────────
    static void drawRepeat(ID2D1RenderTarget* rt, ID2D1Brush* br,
                           float x, float y, float s, float sw) {
        float pad = s * 0.15f;
        float rx = x + pad, ry = y + pad + s*0.1f;
        float rw = s - 2*pad, rh = s * 0.55f;
        // rounded rect outline (only 3 sides, open bottom-left)
        rt->DrawLine({rx + s*0.2f, ry}, {rx + rw, ry}, br, sw);       // top
        rt->DrawLine({rx + rw, ry}, {rx + rw, ry + rh}, br, sw);       // right
        rt->DrawLine({rx + rw, ry + rh}, {rx + s*0.2f, ry + rh}, br, sw); // bottom
        rt->DrawLine({rx + s*0.2f, ry + rh}, {rx, ry + rh*0.5f}, br, sw);
        // arrowhead at top-right
        float ad = s * 0.1f;
        rt->DrawLine({rx + rw - ad, ry - ad}, {rx + rw, ry}, br, sw);
        rt->DrawLine({rx + rw + ad, ry - ad}, {rx + rw, ry}, br, sw);
    }

    static void drawRepeatOne(ID2D1RenderTarget* rt, ID2D1Brush* br,
                              float x, float y, float s, float sw) {
        drawRepeat(rt, br, x, y, s, sw);
        // "1" in center
        float cx = x + s*0.5f, cy = y + s*0.37f;
        rt->DrawLine({cx, cy - s*0.12f}, {cx, cy + s*0.14f}, br, sw);
    }

    // ── Volume ───────────────────────────────────────────────────────────────
    static void drawVolume(ID2D1RenderTarget* rt, ID2D1Brush* br,
                           float x, float y, float s, bool muted, float sw) {
        ID2D1Factory* f = nullptr; rt->GetFactory(&f); if (!f) return;
        // Speaker body
        float cw = s * 0.28f, ch = s * 0.4f;
        float cx = x + s * 0.1f, cy = y + (s - ch) * 0.5f;
        ID2D1PathGeometry* pg = nullptr;
        if (SUCCEEDED(f->CreatePathGeometry(&pg))) {
            ID2D1GeometrySink* sk = nullptr;
            if (SUCCEEDED(pg->Open(&sk))) {
                sk->BeginFigure({cx, cy + ch*0.28f}, D2D1_FIGURE_BEGIN_FILLED);
                sk->AddLine({cx + cw*0.45f, cy + ch*0.28f});
                sk->AddLine({cx + cw, cy});
                sk->AddLine({cx + cw, cy + ch});
                sk->AddLine({cx + cw*0.45f, cy + ch*0.72f});
                sk->AddLine({cx, cy + ch*0.72f});
                sk->EndFigure(D2D1_FIGURE_END_CLOSED);
                sk->Close(); sk->Release();
                rt->FillGeometry(pg, br);
            }
            pg->Release();
        }
        f->Release();
        float wx = cx + cw + s*0.05f;
        if (muted) {
            float mx = wx, my = y + s*0.28f, ms = s * 0.36f;
            rt->DrawLine({mx, my}, {mx+ms, my+ms}, br, sw);
            rt->DrawLine({mx+ms, my}, {mx, my+ms}, br, sw);
        } else {
            rt->DrawLine({wx,       y+s*0.38f}, {wx+s*0.1f, y+s*0.5f}, br, sw);
            rt->DrawLine({wx+s*0.1f,y+s*0.5f}, {wx,        y+s*0.62f}, br, sw);
            rt->DrawLine({wx+s*0.14f,y+s*0.27f},{wx+s*0.24f,y+s*0.5f}, br, sw);
            rt->DrawLine({wx+s*0.24f,y+s*0.5f},{wx+s*0.14f,y+s*0.73f}, br, sw);
        }
    }

    // ── Settings ─────────────────────────────────────────────────────────────
    static void drawSettings(ID2D1RenderTarget* rt, ID2D1Brush* br,
                             float x, float y, float s, float sw) {
        float cx = x + s*0.5f, cy = y + s*0.5f, ri = s*0.14f, ro = s*0.36f;
        rt->DrawEllipse({{cx, cy}, ri, ri}, br, sw);
        for (int i = 0; i < 8; i++) {
            float a = i * kPI / 4.0f;
            float cos_a = std::cos(a), sin_a = std::sin(a);
            float ix = cx + ri * cos_a, iy = cy + ri * sin_a;
            float ox = cx + ro * cos_a, oy = cy + ro * sin_a;
            rt->DrawLine({ix, iy}, {ox, oy}, br, sw*1.4f);
        }
        rt->DrawEllipse({{cx, cy}, ro, ro}, br, sw*0.7f);
    }

    // ── Folder Open ──────────────────────────────────────────────────────────
    static void drawFolder(ID2D1RenderTarget* rt, ID2D1Brush* br, float x, float y, float s) {
        float px = x + s*0.08f, py = y + s*0.22f;
        float fw = s * 0.84f, fh = s * 0.6f;
        float tabW = fw * 0.42f, tabH = fh * 0.22f;
        fillRoundedBar(rt, br, px, py, tabW, tabH + 3.0f, 2.5f);
        fillRoundedBar(rt, br, px, py + tabH, fw, fh - tabH, 3.5f);
    }

    // ── Refresh ──────────────────────────────────────────────────────────────
    static void drawRefresh(ID2D1RenderTarget* rt, ID2D1Brush* br,
                            float x, float y, float s, float sw) {
        float cx = x + s*0.5f, cy = y + s*0.5f, r = s*0.35f;
        // Arc ~270 degrees
        ID2D1Factory* f = nullptr; rt->GetFactory(&f); if (!f) return;
        ID2D1PathGeometry* pg = nullptr;
        if (SUCCEEDED(f->CreatePathGeometry(&pg))) {
            ID2D1GeometrySink* sk = nullptr;
            if (SUCCEEDED(pg->Open(&sk))) {
                sk->SetFillMode(D2D1_FILL_MODE_WINDING);
                float startA = -kPI * 0.1f;
                float sx2 = cx + r * std::cos(startA), sy2 = cy + r * std::sin(startA);
                sk->BeginFigure({sx2, sy2}, D2D1_FIGURE_BEGIN_HOLLOW);
                // Sweep clockwise 270 degrees via arc
                sk->AddArc({{cx + r * std::cos(startA + kPI*1.5f),
                              cy + r * std::sin(startA + kPI*1.5f)},
                             r, r, 0.0f,
                             D2D1_SWEEP_DIRECTION_CLOCKWISE,
                             D2D1_ARC_SIZE_LARGE});
                sk->EndFigure(D2D1_FIGURE_END_OPEN);
                sk->Close(); sk->Release();
                rt->DrawGeometry(pg, br, sw);
            }
            pg->Release();
        }
        f->Release();
        // Arrowhead at end of arc
        float ea = -kPI * 0.1f + kPI * 1.5f;
        float ex = cx + r * std::cos(ea), ey = cy + r * std::sin(ea);
        float ad = s * 0.08f;
        rt->DrawLine({ex - ad, ey - ad}, {ex, ey}, br, sw);
        rt->DrawLine({ex + ad, ey - ad}, {ex, ey}, br, sw);
    }

    // ── Library Music ────────────────────────────────────────────────────────
    static void drawLibraryMusic(ID2D1RenderTarget* rt, ID2D1Brush* br,
                                  float x, float y, float s, float sw) {
        float px = x + s*0.12f, py = y + s*0.1f;
        float w = s * 0.76f, h = s * 0.8f;
        rt->DrawRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(px,py,px+w,py+h), 3.0f,3.0f), br, sw);
        // note inside
        float nx = px + w*0.56f, ny = py + h*0.62f;
        rt->FillEllipse({{nx - w*0.08f, ny}, w*0.09f, w*0.07f}, br);
        rt->DrawLine({nx, ny}, {nx, py + h*0.22f}, br, sw*0.9f);
        rt->DrawLine({nx, py + h*0.22f}, {nx + w*0.2f, py + h*0.3f}, br, sw*0.9f);
    }

    // ── Album (vinyl) ────────────────────────────────────────────────────────
    static void drawAlbum(ID2D1RenderTarget* rt, ID2D1Brush* br,
                          float x, float y, float s, float sw) {
        float cx = x + s*0.5f, cy = y + s*0.5f;
        float ro = s * 0.4f, rm = s * 0.26f, ri = s * 0.1f;
        rt->DrawEllipse({{cx,cy}, ro, ro}, br, sw);
        rt->DrawEllipse({{cx,cy}, rm, rm}, br, sw * 0.6f);
        rt->FillEllipse({{cx,cy}, ri, ri}, br);
    }

    // ── Person ───────────────────────────────────────────────────────────────
    static void drawPerson(ID2D1RenderTarget* rt, ID2D1Brush* br, float x, float y, float s) {
        float cx = x + s*0.5f;
        float hr = s * 0.18f, hy = y + s*0.28f;
        rt->FillEllipse({{cx, hy}, hr, hr}, br);
        float bw = s * 0.52f, bh = s * 0.32f, by = y + s*0.58f;
        rt->FillRoundedRectangle(D2D1::RoundedRect(
            D2D1::RectF(cx-bw/2, by, cx+bw/2, by+bh), bw*0.5f, bh*0.3f), br);
    }

    // ── Heart ────────────────────────────────────────────────────────────────
    static void drawHeart(ID2D1RenderTarget* rt, ID2D1Brush* br,
                          float x, float y, float s, bool filled, float sw = 2.0f) {
        ID2D1Factory* f = nullptr; rt->GetFactory(&f); if (!f) return;
        ID2D1PathGeometry* pg = nullptr;
        if (SUCCEEDED(f->CreatePathGeometry(&pg))) {
            ID2D1GeometrySink* sk = nullptr;
            if (SUCCEEDED(pg->Open(&sk))) {
                float cx = x + s*0.5f, ty = y + s*0.3f, r = s*0.22f;
                sk->BeginFigure({cx, ty + r*1.9f},
                    filled ? D2D1_FIGURE_BEGIN_FILLED : D2D1_FIGURE_BEGIN_HOLLOW);
                sk->AddBezier({{cx-r*1.4f, ty+r*0.7f},{cx-r*1.4f, ty-r*0.4f},{cx-r*0.65f, ty-r*0.8f}});
                sk->AddBezier({{cx-r*0.2f, ty-r*0.8f},{cx, ty-r*0.15f},{cx, ty-r*0.15f}});
                sk->AddBezier({{cx, ty-r*0.15f},{cx+r*0.2f, ty-r*0.8f},{cx+r*0.65f, ty-r*0.8f}});
                sk->AddBezier({{cx+r*1.4f, ty-r*0.4f},{cx+r*1.4f, ty+r*0.7f},{cx, ty+r*1.9f}});
                sk->EndFigure(D2D1_FIGURE_END_CLOSED);
                sk->Close(); sk->Release();
                if (filled) rt->FillGeometry(pg, br);
                else        rt->DrawGeometry(pg, br, sw);
            }
            pg->Release();
        }
        f->Release();
    }

    // ── Visibility Off ───────────────────────────────────────────────────────
    static void drawVisibilityOff(ID2D1RenderTarget* rt, ID2D1Brush* br,
                                   float x, float y, float s, float sw) {
        float cx = x+s*0.5f, cy = y+s*0.5f, rx = s*0.35f, ry = rx*0.65f;
        rt->DrawEllipse({{cx,cy}, rx, ry}, br, sw);
        rt->FillEllipse({{cx,cy}, rx*0.35f, rx*0.35f}, br);
        rt->DrawLine({x+s*0.08f, y+s*0.88f}, {x+s*0.92f, y+s*0.12f}, br, sw);
    }

    // ── Search ───────────────────────────────────────────────────────────────
    static void drawSearch(ID2D1RenderTarget* rt, ID2D1Brush* br,
                           float x, float y, float s, float sw) {
        float r = s * 0.3f, cx = x+s*0.38f, cy = y+s*0.38f;
        rt->DrawEllipse({{cx,cy}, r, r}, br, sw);
        float hx = cx + r*0.71f, hy = cy + r*0.71f;
        rt->DrawLine({hx, hy}, {x+s*0.9f, y+s*0.9f}, br, sw*1.1f);
    }

    // ── Add / Plus ───────────────────────────────────────────────────────────
    static void drawAdd(ID2D1RenderTarget* rt, ID2D1Brush* br,
                        float x, float y, float s, float sw) {
        float cx = x+s*0.5f, cy = y+s*0.5f, half = s*0.38f;
        rt->DrawLine({cx-half,cy}, {cx+half,cy}, br, sw);
        rt->DrawLine({cx,cy-half}, {cx,cy+half}, br, sw);
    }

    // ── More Vert ────────────────────────────────────────────────────────────
    static void drawMoreVert(ID2D1RenderTarget* rt, ID2D1Brush* br, float x, float y, float s) {
        float cx = x+s*0.5f, r = s*0.065f;
        rt->FillEllipse({{cx, y+s*0.25f}, r, r}, br);
        rt->FillEllipse({{cx, y+s*0.5f},  r, r}, br);
        rt->FillEllipse({{cx, y+s*0.75f}, r, r}, br);
    }

    // ── Queue Music ──────────────────────────────────────────────────────────
    static void drawQueueMusic(ID2D1RenderTarget* rt, ID2D1Brush* br,
                               float x, float y, float s, float sw) {
        float lx = x+s*0.12f, w = s*0.56f;
        rt->DrawLine({lx, y+s*0.28f}, {lx+w, y+s*0.28f}, br, sw);
        rt->DrawLine({lx, y+s*0.46f}, {lx+w, y+s*0.46f}, br, sw);
        rt->DrawLine({lx, y+s*0.64f}, {lx+w*0.72f, y+s*0.64f}, br, sw);
        // note
        float nx = x+s*0.82f, ny = y+s*0.62f;
        rt->FillEllipse({{nx-s*0.04f, ny}, s*0.065f, s*0.052f}, br);
        rt->DrawLine({nx, ny}, {nx, y+s*0.3f}, br, sw*0.8f);
    }

    // ── Chevron ──────────────────────────────────────────────────────────────
    static void drawChevron(ID2D1RenderTarget* rt, ID2D1Brush* br,
                            float x, float y, float s, bool right, float sw) {
        float cx = x + s * (right ? 0.42f : 0.58f);
        float cy = y + s * 0.5f;
        float arm = s * 0.22f;
        float dir = right ? 1.0f : -1.0f;
        rt->DrawLine({cx - arm*dir, cy - arm}, {cx + arm*0.3f*dir, cy}, br, sw);
        rt->DrawLine({cx + arm*0.3f*dir, cy}, {cx - arm*dir, cy + arm}, br, sw);
    }

    // ── Close ────────────────────────────────────────────────────────────────
    static void drawClose(ID2D1RenderTarget* rt, ID2D1Brush* br,
                          float x, float y, float s, float sw) {
        float p = s * 0.22f;
        rt->DrawLine({x+p, y+p}, {x+s-p, y+s-p}, br, sw);
        rt->DrawLine({x+s-p, y+p}, {x+p, y+s-p}, br, sw);
    }

    // ── Check ────────────────────────────────────────────────────────────────
    static void drawCheck(ID2D1RenderTarget* rt, ID2D1Brush* br,
                          float x, float y, float s, float sw) {
        rt->DrawLine({x+s*0.15f, y+s*0.5f}, {x+s*0.42f, y+s*0.72f}, br, sw);
        rt->DrawLine({x+s*0.42f, y+s*0.72f}, {x+s*0.85f, y+s*0.28f}, br, sw);
    }
};
