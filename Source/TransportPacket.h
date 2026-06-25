#pragma once
#include <JuceHeader.h>

struct TransportState
{
    juce::String timecode { "00:00:00:00" };
    bool isPlaying = false;
    bool isRecording = false;
    double fps = 30.0;

    juce::String toJson() const
    {
        juce::String json;
        json << "{"
             << "\"tc\":\"" << timecode << "\","
             << "\"play\":" << (isPlaying ? 1 : 0) << ","
             << "\"rec\":" << (isRecording ? 1 : 0) << ","
             << "\"fps\":" << juce::String(fps, 2)
             << "}";
        return json;
    }
};
