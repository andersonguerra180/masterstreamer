#pragma once
#include <cstdint>

#pragma pack(push, 1)

/**
 * MASTERSTREAMER Audio Packet Struct
 * Designed for low-latency UDP streaming of 16-bit PCM stereo audio.
 */
struct AudioPacketHeader
{
    uint32_t magic;         // Hex: 'M' 'S' 'A' 'U' (0x5541534D)
    uint32_t sequence;      // Incrementing sequence number
    uint32_t sampleRate;    // E.g. 44100, 48000
    uint16_t numChannels;   // Channel count (typically 2 for stereo)
    uint16_t numSamples;    // Number of frames in this packet (e.g. 256)
    uint16_t bitDepth;      // 16 for 16-bit PCM
};

struct AudioPacket
{
    AudioPacketHeader header;
    int16_t payload[512]; // Buffer for 256 stereo frames (256 * 2 = 512 total samples)
};

#pragma pack(pop)
