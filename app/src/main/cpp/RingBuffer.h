#ifndef AUDIOPLAYER_RINGBUFFER_H
#define AUDIOPLAYER_RINGBUFFER_H

#include <atomic>
#include <vector>
#include <cstdint>
#include <algorithm>

/**
 * A lock-free Ring Buffer for passing audio samples between the decoder thread
 * and the Oboe audio callback thread.
 */
template <typename T>
class RingBuffer {
public:
    explicit RingBuffer(int32_t capacity)
        : mCapacity(capacity), mBuffer(capacity), mReadIndex(0), mWriteIndex(0) {
    }

    /**
     * Write data into the buffer.
     * @param data Pointer to the data to write.
     * @param count Number of elements to write.
     * @return Number of elements actually written.
     */
    int32_t write(const T* data, int32_t count) {
        uint32_t gen = mGeneration.load(std::memory_order_acquire);
        int32_t available = getAvailableWrite();
        if (available <= 0 || count <= 0) return 0;

        int32_t toWrite = std::min(count, available);
        int32_t writeIndex = mWriteIndex.load(std::memory_order_relaxed);

        int32_t firstPart = std::min(toWrite, mCapacity - writeIndex);
        std::copy(data, data + firstPart, mBuffer.data() + writeIndex);

        int32_t secondPart = toWrite - firstPart;
        if (secondPart > 0) {
            std::copy(data + firstPart, data + toWrite, mBuffer.data());
        }

        if (mGeneration.load(std::memory_order_acquire) != gen) {
            return 0;
        }
        mWriteIndex.store((writeIndex + toWrite) % mCapacity, std::memory_order_release);
        return toWrite;
    }

    /**
     * Read data from the buffer.
     * @param data Pointer to the destination array.
     * @param count Number of elements to read.
     * @return Number of elements actually read.
     */
    int32_t read(T* data, int32_t count) {
        uint32_t gen = mGeneration.load(std::memory_order_acquire);
        int32_t available = getAvailableRead();
        if (available <= 0 || count <= 0) return 0;

        int32_t toRead = std::min(count, available);
        int32_t readIndex = mReadIndex.load(std::memory_order_relaxed);

        int32_t firstPart = std::min(toRead, mCapacity - readIndex);
        std::copy(mBuffer.data() + readIndex, mBuffer.data() + readIndex + firstPart, data);

        int32_t secondPart = toRead - firstPart;
        if (secondPart > 0) {
            std::copy(mBuffer.data(), mBuffer.data() + secondPart, data + firstPart);
        }

        if (mGeneration.load(std::memory_order_acquire) != gen) {
            return 0;
        }
        mReadIndex.store((readIndex + toRead) % mCapacity, std::memory_order_release);
        return toRead;
    }

    int32_t getAvailableRead() const {
        int32_t w = mWriteIndex.load(std::memory_order_acquire);
        int32_t r = mReadIndex.load(std::memory_order_acquire);
        if (w >= r) {
            return w - r;
        } else {
            return mCapacity - r + w;
        }
    }

    int32_t getAvailableWrite() const {
        // Reserve 1 slot to distinguish between empty and full
        return mCapacity - getAvailableRead() - 1;
    }

    void clear() {
        mGeneration.fetch_add(1, std::memory_order_acq_rel);
        mReadIndex.store(0, std::memory_order_release);
        mWriteIndex.store(0, std::memory_order_release);
    }

    int32_t getCapacity() const {
        return mCapacity;
    }

private:
    int32_t mCapacity;
    std::vector<T> mBuffer;
    std::atomic<int32_t> mReadIndex;
    std::atomic<int32_t> mWriteIndex;
    std::atomic<uint32_t> mGeneration{0};
};

#endif //AUDIOPLAYER_RINGBUFFER_H
