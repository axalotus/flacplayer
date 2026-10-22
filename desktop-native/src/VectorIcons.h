#pragma once

#include <d2d1.h>
#include <cmath>

class VectorIcons {
public:
    // Draw Play Triangle
    static void drawPlay(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        ID2D1Factory* factory = nullptr;
        rt->GetFactory(&factory);
        if (!factory) return;

        ID2D1PathGeometry* path = nullptr;
        if (SUCCEEDED(factory->CreatePathGeometry(&path))) {
            ID2D1GeometrySink* sink = nullptr;
            if (SUCCEEDED(path->Open(&sink))) {
                sink->BeginFigure(D2D1::Point2F(x, y), D2D1_FIGURE_BEGIN_FILLED);
                sink->AddLine(D2D1::Point2F(x + size, y + size * 0.5f));
                sink->AddLine(D2D1::Point2F(x, y + size));
                sink->EndFigure(D2D1_FIGURE_END_CLOSED);
                sink->Close();
                sink->Release();

                rt->FillGeometry(path, brush);
            }
            path->Release();
        }
    }

    // Draw Pause Bars
    static void drawPause(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        float barW = size * 0.3f;
        float gap = size * 0.4f;
        rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x, y, x + barW, y + size), 2.0f, 2.0f), brush);
        rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x + barW + gap, y, x + 2 * barW + gap, y + size), 2.0f, 2.0f), brush);
    }

    // Draw Next Track (triangle + bar)
    static void drawNext(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        drawPlay(rt, brush, x, y, size * 0.75f);
        float barX = x + size * 0.82f;
        float barW = size * 0.18f;
        rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(barX, y, barX + barW, y + size * 0.75f), 1.5f, 1.5f), brush);
    }

    // Draw Previous Track (bar + reverse triangle)
    static void drawPrev(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        ID2D1Factory* factory = nullptr;
        rt->GetFactory(&factory);
        if (!factory) return;

        float h = size * 0.75f;
        float barW = size * 0.18f;
        rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x, y, x + barW, y + h), 1.5f, 1.5f), brush);

        float triX = x + barW + size * 0.07f;
        ID2D1PathGeometry* path = nullptr;
        if (SUCCEEDED(factory->CreatePathGeometry(&path))) {
            ID2D1GeometrySink* sink = nullptr;
            if (SUCCEEDED(path->Open(&sink))) {
                sink->BeginFigure(D2D1::Point2F(triX + h, y), D2D1_FIGURE_BEGIN_FILLED);
                sink->AddLine(D2D1::Point2F(triX, y + h * 0.5f));
                sink->AddLine(D2D1::Point2F(triX + h, y + h));
                sink->EndFigure(D2D1_FIGURE_END_CLOSED);
                sink->Close();
                sink->Release();

                rt->FillGeometry(path, brush);
            }
            path->Release();
        }
    }

    // Draw Volume / Speaker Icon
    static void drawSpeaker(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size, bool isMuted = false) {
        ID2D1Factory* factory = nullptr;
        rt->GetFactory(&factory);
        if (!factory) return;

        float sw = size * 0.35f;
        float sh = size * 0.5f;
        float sy = y + (size - sh) * 0.5f;

        // Speaker cone
        ID2D1PathGeometry* path = nullptr;
        if (SUCCEEDED(factory->CreatePathGeometry(&path))) {
            ID2D1GeometrySink* sink = nullptr;
            if (SUCCEEDED(path->Open(&sink))) {
                sink->BeginFigure(D2D1::Point2F(x, sy + sh * 0.25f), D2D1_FIGURE_BEGIN_FILLED);
                sink->AddLine(D2D1::Point2F(x + sw * 0.4f, sy + sh * 0.25f));
                sink->AddLine(D2D1::Point2F(x + sw, sy));
                sink->AddLine(D2D1::Point2F(x + sw, sy + sh));
                sink->AddLine(D2D1::Point2F(x + sw * 0.4f, sy + sh * 0.75f));
                sink->AddLine(D2D1::Point2F(x, sy + sh * 0.75f));
                sink->EndFigure(D2D1_FIGURE_END_CLOSED);
                sink->Close();
                sink->Release();

                rt->FillGeometry(path, brush);
            }
            path->Release();
        }

        // Sound waves or Mute cross
        if (isMuted) {
            float crossX = x + sw + size * 0.15f;
            float crossY = y + size * 0.3f;
            float crossS = size * 0.4f;
            rt->DrawLine(D2D1::Point2F(crossX, crossY), D2D1::Point2F(crossX + crossS, crossY + crossS), brush, 2.0f);
            rt->DrawLine(D2D1::Point2F(crossX + crossS, crossY), D2D1::Point2F(crossX, crossY + crossS), brush, 2.0f);
        } else {
            float arcX = x + sw + size * 0.12f;
            rt->DrawLine(D2D1::Point2F(arcX, y + size * 0.35f), D2D1::Point2F(arcX + size * 0.15f, y + size * 0.5f), brush, 2.0f);
            rt->DrawLine(D2D1::Point2F(arcX + size * 0.15f, y + size * 0.5f), D2D1::Point2F(arcX, y + size * 0.65f), brush, 2.0f);

            float arc2X = arcX + size * 0.18f;
            rt->DrawLine(D2D1::Point2F(arc2X, y + size * 0.25f), D2D1::Point2F(arc2X + size * 0.2f, y + size * 0.5f), brush, 2.0f);
            rt->DrawLine(D2D1::Point2F(arc2X + size * 0.2f, y + size * 0.5f), D2D1::Point2F(arc2X, y + size * 0.75f), brush, 2.0f);
        }
    }

    // Draw Folder Icon
    static void drawFolder(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        float w = size;
        float h = size * 0.75f;
        float tabW = size * 0.45f;
        float tabH = size * 0.25f;

        rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x, y, x + tabW, y + tabH + 2.0f), 2.0f, 2.0f), brush);
        rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(x, y + tabH, x + w, y + h), 3.0f, 3.0f), brush);
    }

    // Draw Plus Badge
    static void drawPlus(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        float midX = x + size * 0.5f;
        float midY = y + size * 0.5f;
        rt->DrawLine(D2D1::Point2F(x, midY), D2D1::Point2F(x + size, midY), brush, 2.0f);
        rt->DrawLine(D2D1::Point2F(midX, y), D2D1::Point2F(midX, y + size), brush, 2.0f);
    }

    // Draw Search / Magnifying Glass Icon
    static void drawSearch(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        float r = size * 0.35f;
        float cx = x + r + 2.0f;
        float cy = y + r + 2.0f;
        rt->DrawEllipse(D2D1::Ellipse(D2D1::Point2F(cx, cy), r, r), brush, 2.0f);
        float handleStart = r * 0.707f;
        rt->DrawLine(D2D1::Point2F(cx + handleStart, cy + handleStart), D2D1::Point2F(x + size - 1.0f, y + size - 1.0f), brush, 2.5f);
    }

    // Draw Music Note Icon
    static void drawMusicNote(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        float stemH = size * 0.7f;
        float noteR = size * 0.2f;

        rt->FillEllipse(D2D1::Ellipse(D2D1::Point2F(x + noteR + 2.0f, y + stemH), noteR, noteR * 0.75f), brush);
        rt->DrawLine(D2D1::Point2F(x + noteR * 2.0f, y + stemH), D2D1::Point2F(x + noteR * 2.0f, y + 2.0f), brush, 2.0f);
        rt->DrawLine(D2D1::Point2F(x + noteR * 2.0f, y + 2.0f), D2D1::Point2F(x + size - 2.0f, y + size * 0.3f), brush, 2.5f);
    }

    // Draw Shuffle Icon
    static void drawShuffle(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        float h = size * 0.6f;
        float sy = y + (size - h) * 0.5f;
        rt->DrawLine(D2D1::Point2F(x, sy), D2D1::Point2F(x + size * 0.4f, sy), brush, 2.0f);
        rt->DrawLine(D2D1::Point2F(x + size * 0.4f, sy), D2D1::Point2F(x + size * 0.7f, sy + h), brush, 2.0f);
        rt->DrawLine(D2D1::Point2F(x + size * 0.7f, sy + h), D2D1::Point2F(x + size, sy + h), brush, 2.0f);

        rt->DrawLine(D2D1::Point2F(x, sy + h), D2D1::Point2F(x + size * 0.4f, sy + h), brush, 2.0f);
        rt->DrawLine(D2D1::Point2F(x + size * 0.4f, sy + h), D2D1::Point2F(x + size * 0.7f, sy), brush, 2.0f);
        rt->DrawLine(D2D1::Point2F(x + size * 0.7f, sy), D2D1::Point2F(x + size, sy), brush, 2.0f);
    }

    // Draw Repeat Icon
    static void drawRepeat(ID2D1RenderTarget* rt, ID2D1Brush* brush, float x, float y, float size) {
        float r = size * 0.4f;
        float cx = x + size * 0.5f;
        float cy = y + size * 0.5f;
        rt->DrawEllipse(D2D1::Ellipse(D2D1::Point2F(cx, cy), r, r), brush, 2.0f);
        rt->FillRectangle(D2D1::RectF(cx, cy - r - 4.0f, cx + 8.0f, cy - r + 4.0f), brush);
    }
};
