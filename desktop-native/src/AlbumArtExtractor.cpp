#include "AlbumArtExtractor.h"
#include <fstream>
#include <algorithm>

AlbumArtExtractor& AlbumArtExtractor::getInstance() {
    static AlbumArtExtractor instance;
    return instance;
}

AlbumArtExtractor::AlbumArtExtractor() = default;

AlbumArtExtractor::~AlbumArtExtractor() {
    clearCache();
}

void AlbumArtExtractor::init(ID2D1RenderTarget* rt, IWICImagingFactory* wicFactory) {
    m_pWICFactory = wicFactory;
}

void AlbumArtExtractor::clearCache() {
    std::lock_guard<std::mutex> lock(m_cacheMutex);
    for (auto& pair : m_bitmapCache) {
        if (pair.second) pair.second->Release();
    }
    m_bitmapCache.clear();
}

ID2D1Bitmap* AlbumArtExtractor::loadFromMemory(const uint8_t* data, size_t size, ID2D1RenderTarget* rt) {
    if (!m_pWICFactory || !rt || !data || size == 0) return nullptr;

    IWICStream* pStream = nullptr;
    if (FAILED(m_pWICFactory->CreateStream(&pStream))) return nullptr;

    if (FAILED(pStream->InitializeFromMemory(const_cast<BYTE*>(data), (DWORD)size))) {
        pStream->Release();
        return nullptr;
    }

    IWICBitmapDecoder* pDecoder = nullptr;
    if (FAILED(m_pWICFactory->CreateDecoderFromStream(pStream, nullptr, WICDecodeMetadataCacheOnLoad, &pDecoder))) {
        pStream->Release();
        return nullptr;
    }

    IWICBitmapFrameDecode* pFrame = nullptr;
    if (FAILED(pDecoder->GetFrame(0, &pFrame))) {
        pDecoder->Release();
        pStream->Release();
        return nullptr;
    }

    IWICFormatConverter* pConverter = nullptr;
    if (FAILED(m_pWICFactory->CreateFormatConverter(&pConverter))) {
        pFrame->Release();
        pDecoder->Release();
        pStream->Release();
        return nullptr;
    }

    if (FAILED(pConverter->Initialize(pFrame, GUID_WICPixelFormat32bppPBGRA, WICBitmapDitherTypeNone, nullptr, 0.0f, WICBitmapPaletteTypeMedianCut))) {
        pConverter->Release();
        pFrame->Release();
        pDecoder->Release();
        pStream->Release();
        return nullptr;
    }

    ID2D1Bitmap* pBitmap = nullptr;
    rt->CreateBitmapFromWicBitmap(pConverter, nullptr, &pBitmap);

    pConverter->Release();
    pFrame->Release();
    pDecoder->Release();
    pStream->Release();

    return pBitmap;
}

ID2D1Bitmap* AlbumArtExtractor::loadFromFile(const std::wstring& filePath, ID2D1RenderTarget* rt) {
    if (!m_pWICFactory || !rt) return nullptr;

    IWICBitmapDecoder* pDecoder = nullptr;
    if (FAILED(m_pWICFactory->CreateDecoderFromFilename(filePath.c_str(), nullptr, GENERIC_READ, WICDecodeMetadataCacheOnLoad, &pDecoder))) {
        return nullptr;
    }

    IWICBitmapFrameDecode* pFrame = nullptr;
    if (FAILED(pDecoder->GetFrame(0, &pFrame))) {
        pDecoder->Release();
        return nullptr;
    }

    IWICFormatConverter* pConverter = nullptr;
    if (FAILED(m_pWICFactory->CreateFormatConverter(&pConverter))) {
        pFrame->Release();
        pDecoder->Release();
        return nullptr;
    }

    if (FAILED(pConverter->Initialize(pFrame, GUID_WICPixelFormat32bppPBGRA, WICBitmapDitherTypeNone, nullptr, 0.0f, WICBitmapPaletteTypeMedianCut))) {
        pConverter->Release();
        pFrame->Release();
        pDecoder->Release();
        return nullptr;
    }

    ID2D1Bitmap* pBitmap = nullptr;
    rt->CreateBitmapFromWicBitmap(pConverter, nullptr, &pBitmap);

    pConverter->Release();
    pFrame->Release();
    pDecoder->Release();

    return pBitmap;
}

bool AlbumArtExtractor::extractFlacPicture(const std::string& filePath, std::vector<uint8_t>& outBytes) {
    std::ifstream file(filePath, std::ios::binary);
    if (!file.is_open()) return false;

    char magic[4];
    file.read(magic, 4);
    if (memcmp(magic, "fLaC", 4) != 0) return false;

    bool isLast = false;
    while (!isLast && file.good()) {
        uint8_t header[4];
        file.read((char*)header, 4);
        if (file.gcount() < 4) break;

        isLast = (header[0] & 0x80) != 0;
        uint8_t type = header[0] & 0x7F;
        uint32_t length = ((uint32_t)header[1] << 16) | ((uint32_t)header[2] << 8) | (uint32_t)header[3];

        if (type == 6) { // METADATA_BLOCK_PICTURE
            std::vector<uint8_t> block(length);
            file.read((char*)block.data(), length);
            if (file.gcount() < length) break;

            // Parse FLAC picture structure:
            // 4 bytes: picture type
            // 4 bytes: MIME type length (L)
            // L bytes: MIME string
            // 4 bytes: description length (D)
            // D bytes: description string
            // 4 bytes: width, 4 bytes: height, 4 bytes: depth, 4 bytes: colors
            // 4 bytes: data length (N)
            // N bytes: picture data
            size_t offset = 4;
            if (offset + 4 > length) break;
            uint32_t mimeLen = ((uint32_t)block[offset] << 24) | ((uint32_t)block[offset+1] << 16) | ((uint32_t)block[offset+2] << 8) | (uint32_t)block[offset+3];
            offset += 4 + mimeLen;

            if (offset + 4 > length) break;
            uint32_t descLen = ((uint32_t)block[offset] << 24) | ((uint32_t)block[offset+1] << 16) | ((uint32_t)block[offset+2] << 8) | (uint32_t)block[offset+3];
            offset += 4 + descLen + 16; // skip desc + width/height/depth/colors

            if (offset + 4 > length) break;
            uint32_t dataLen = ((uint32_t)block[offset] << 24) | ((uint32_t)block[offset+1] << 16) | ((uint32_t)block[offset+2] << 8) | (uint32_t)block[offset+3];
            offset += 4;

            if (offset + dataLen <= length && dataLen > 0) {
                outBytes.assign(block.begin() + offset, block.begin() + offset + dataLen);
                return true;
            }
            break;
        } else {
            file.seekg(length, std::ios::cur);
        }
    }
    return false;
}

bool AlbumArtExtractor::extractId3Picture(const std::string& filePath, std::vector<uint8_t>& outBytes) {
    std::ifstream file(filePath, std::ios::binary);
    if (!file.is_open()) return false;

    uint8_t id3Header[10];
    file.read((char*)id3Header, 10);
    if (file.gcount() < 10 || memcmp(id3Header, "ID3", 3) != 0) return false;

    uint32_t tagSize = ((id3Header[6] & 0x7F) << 21) | ((id3Header[7] & 0x7F) << 14) |
                       ((id3Header[8] & 0x7F) << 7) | (id3Header[9] & 0x7F);

    std::vector<uint8_t> tagData(tagSize);
    file.read((char*)tagData.data(), tagSize);
    if (file.gcount() < tagSize) return false;

    size_t offset = 0;
    while (offset + 10 < tagSize) {
        char frameId[5] = {0};
        memcpy(frameId, &tagData[offset], 4);
        if (frameId[0] == 0) break; // Padding

        uint32_t frameSize = ((uint32_t)tagData[offset + 4] << 24) | ((uint32_t)tagData[offset + 5] << 16) |
                             ((uint32_t)tagData[offset + 6] << 8) | (uint32_t)tagData[offset + 7];
        
        // Handle synchsafe or normal int
        if (id3Header[3] >= 4) {
            frameSize = ((tagData[offset + 4] & 0x7F) << 21) | ((tagData[offset + 5] & 0x7F) << 14) |
                        ((tagData[offset + 6] & 0x7F) << 7) | (tagData[offset + 7] & 0x7F);
        }

        offset += 10;
        if (strcmp(frameId, "APIC") == 0 && offset + frameSize <= tagSize) {
            // APIC frame structure:
            // 1 byte text encoding
            // MIME string (null-terminated)
            // 1 byte picture type
            // Description (null-terminated, based on encoding)
            // Image data bytes
            size_t pOffset = offset + 1; // skip encoding
            while (pOffset < offset + frameSize && tagData[pOffset] != 0) pOffset++;
            pOffset++; // skip null
            pOffset++; // skip pic type
            while (pOffset < offset + frameSize && tagData[pOffset] != 0) pOffset++;
            pOffset++; // skip desc null

            if (pOffset < offset + frameSize) {
                outBytes.assign(tagData.begin() + pOffset, tagData.begin() + offset + frameSize);
                return true;
            }
            break;
        }
        offset += frameSize;
    }
    return false;
}

ID2D1Bitmap* AlbumArtExtractor::getAlbumArt(const std::string& audioFilePath, ID2D1RenderTarget* rt) {
    if (audioFilePath.empty() || !rt) return nullptr;

    std::lock_guard<std::mutex> lock(m_cacheMutex);
    auto it = m_bitmapCache.find(audioFilePath);
    if (it != m_bitmapCache.end()) {
        return it->second;
    }

    ID2D1Bitmap* bitmap = nullptr;

    // 1. Check embedded in FLAC
    std::vector<uint8_t> imgBytes;
    if (extractFlacPicture(audioFilePath, imgBytes)) {
        bitmap = loadFromMemory(imgBytes.data(), imgBytes.size(), rt);
    }

    // 2. Check embedded in MP3 (ID3 APIC)
    if (!bitmap && extractId3Picture(audioFilePath, imgBytes)) {
        bitmap = loadFromMemory(imgBytes.data(), imgBytes.size(), rt);
    }

    // 3. Check sidecar files in folder
    if (!bitmap) {
        size_t lastSlash = audioFilePath.find_last_of("\\/");
        if (lastSlash != std::string::npos) {
            std::string folder = audioFilePath.substr(0, lastSlash);
            const char* sidecars[] = { "\\cover.jpg", "\\cover.png", "\\folder.jpg", "\\folder.png", "\\album.jpg", "\\front.jpg" };
            for (const char* sc : sidecars) {
                std::string scPath = folder + sc;
                int len = MultiByteToWideChar(CP_UTF8, 0, scPath.c_str(), -1, nullptr, 0);
                if (len > 0) {
                    std::wstring wPath(len, 0);
                    MultiByteToWideChar(CP_UTF8, 0, scPath.c_str(), -1, &wPath[0], len);
                    bitmap = loadFromFile(wPath, rt);
                    if (bitmap) break;
                }
            }
        }
    }

    m_bitmapCache[audioFilePath] = bitmap;
    return bitmap;
}
