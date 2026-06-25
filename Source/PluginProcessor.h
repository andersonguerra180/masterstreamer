#pragma once
#include <JuceHeader.h>
#include <atomic>
#include "AudioPacket.h"
#include "TransportPacket.h"

// Asynchronous background thread responsible for packetizing and transmitting
// audio frames over UDP, preventing networking jitter from stalling the DAW.
class AudioSenderThread : public juce::Thread
{
public:
    AudioSenderThread();
    ~AudioSenderThread() override;

    void run() override;
    
    void configure(const juce::String& targetIp, int targetPort);
    void setEnabled(bool enable);
    bool isStreamingActive() const { return streamingEnabled.load(); }
    uint32_t getPacketCount() const { return packetCount.load(); }

    void pushAudio(const juce::AudioBuffer<float>& buffer);
    void prepare(double sampleRate, int maxBlockSize);

private:
    std::atomic<bool> streamingEnabled { false };
    double currentSampleRate { 44100.0 };
    
    juce::String ipAddress { "255.255.255.255" };
    int port { 9002 };

    std::atomic<uint32_t> packetCount { 0 };

    // Thread-safe lock-free circular buffer
    juce::AbstractFifo fifo { 65536 };
    juce::AudioBuffer<float> ringBuffer;
    
    juce::CriticalSection configCriticalSection;

    static inline int16_t floatToPCM16(float sample)
    {
        float scaled = sample * 32767.0f;
        if (scaled > 32767.0f) return 32767;
        if (scaled < -32768.0f) return -32768;
        return static_cast<int16_t>(scaled);
    }

    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR(AudioSenderThread)
};


// Asynchronous background thread responsible for packetizing and transmitting
// playhead timecode and transport state to port 9003 at ~30Hz.
class TransportSenderThread : public juce::Thread
{
public:
    TransportSenderThread();
    ~TransportSenderThread() override;

    void run() override;
    
    void configure(const juce::String& targetIp, int targetPort);
    void setEnabled(bool enable);
    bool isStreamingActive() const { return streamingEnabled.load(); }
    uint32_t getPacketCount() const { return packetCount.load(); }

    void updateState(const TransportState& newState);

private:
    std::atomic<bool> streamingEnabled { false };
    
    juce::String ipAddress { "255.255.255.255" };
    int port { 9003 };

    std::atomic<uint32_t> packetCount { 0 };

    juce::CriticalSection stateCriticalSection;
    TransportState currentState;

    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR(TransportSenderThread)
};


class MasterStreamerAudioProcessor : public juce::AudioProcessor
{
public:
    MasterStreamerAudioProcessor();
    ~MasterStreamerAudioProcessor() override;

    void prepareToPlay(double sampleRate, int samplesPerBlock) override;
    void releaseResources() override;
    void processBlock(juce::AudioBuffer<float>&, juce::MidiBuffer&) override;

    juce::AudioProcessorEditor* createEditor() override;
    bool hasEditor() const override { return true; }

    const juce::String getName() const override { return "MasterStreamer"; }
    bool acceptsMidi() const override { return false; }
    bool producesMidi() const override { return false; }
    double getTailLengthSeconds() const override { return 0.0; }

    int getNumPrograms() override { return 1; }
    int getCurrentProgram() override { return 0; }
    void setCurrentProgram(int) override {}
    const juce::String getProgramName(int) override { return {}; }
    void changeProgramName(int, const juce::String&) override {}

    void getStateInformation(juce::MemoryBlock& destData) override;
    void setStateInformation(const void* data, int sizeInBytes) override;

    AudioSenderThread& getSender() { return senderThread; }
    TransportSenderThread& getTransportSender() { return transportSenderThread; }
    
    // Configurable parameters stored directly as class members
    juce::String targetIpAddress { "255.255.255.255" };
    int targetPortNumber { 9002 };
    bool isStreamingEnabled { false };

    juce::String transportIpAddress { "255.255.255.255" };
    int transportPortNumber { 9003 };
    bool isTransportStreamingEnabled { false };

    static juce::String formatTimecode(double timeInSeconds, double fps)
    {
        if (timeInSeconds < 0.0) timeInSeconds = 0.0;
        if (fps <= 0.0) fps = 30.0;

        int64_t fpsInt = static_cast<int64_t>(std::round(fps));
        if (fpsInt <= 0) fpsInt = 30;

        int64_t totalFrames = static_cast<int64_t>(std::round(timeInSeconds * fps));

        int64_t frames = totalFrames % fpsInt;
        int64_t totalSeconds = totalFrames / fpsInt;

        int64_t seconds = totalSeconds % 60;
        int64_t minutes = (totalSeconds / 60) % 60;
        int64_t hours = totalSeconds / 3600;

        return juce::String::formatted("%02d:%02d:%02d:%02d",
                                       static_cast<int>(hours),
                                       static_cast<int>(minutes),
                                       static_cast<int>(seconds),
                                       static_cast<int>(frames));
    }

private:
    AudioSenderThread senderThread;
    TransportSenderThread transportSenderThread;
    juce::String lastTimecode { "00:00:00:00" };

    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR(MasterStreamerAudioProcessor)
};
