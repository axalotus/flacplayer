#pragma once

#include <windows.h>
#include <d2d1.h>
#include <wincodec.h>
#include <string>
#include <vector>
#include <map>
#include <mutex>

class AlbumArtExtractor {
public:
    static AlbumArtExtractor& getInstance();

    void init(ID2D1RenderTarget* rt, IWICImagingFactory* wicFactory);
    ID2D1Bitmap* getAlbumArt(const std::string& audioFilePath, ID2D1RenderTarget* rt);
    void clearCache();

private:
    AlbumArtExtractor();
    ~AlbumArtExtractor();

    ID2D1Bitmap* loadFromMemory(const uint8_t* data, size_t size, ID2D1RenderTarget* rt);
    ID2D1Bitmap* loadFromFile(const std::wstring& filePath, ID2D1RenderTarget* rt);
    bool extractFlacPicture(const std::string& filePath, std::vector<uint8_t>& outBytes);
    bool extractId3Picture(const std::string& filePath, std::vector<uint8_t>& outBytes);

    IWICImagingFactory* m_pWICFactory = nullptr;
    std::map<std::string, ID2D1Bitmap*> m_bitmapCache;
    std::mutex m_cacheMutex;
};
