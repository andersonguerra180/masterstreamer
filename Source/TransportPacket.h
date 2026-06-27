#pragma once
#include <JuceHeader.h>

struct TransportState
{
    juce::String timecode { "00:00:00:00" };
    bool isPlaying = false;
    bool isRecording = false;
    double fps = 30.0;

    // --- NEW: musical sync fields for metronome ---
    double bpm = 120.0;
    double ppqPosition = 0.0;   // absolute position in quarter notes
    int timeSigNum = 4;
    int timeSigDen = 4;

    juce::String toJson() const
    {
        juce::String json;
        json << "{"
             << "\"tc\":\"" << timecode << "\","
             << "\"play\":" << (isPlaying ? 1 : 0) << ","
             << "\"rec\":" << (isRecording ? 1 : 0) << ","
             << "\"fps\":" << juce::String(fps, 2) << ","
             << "\"bpm\":" << juce::String(bpm, 3) << ","
             << "\"ppq\":" << juce::String(ppqPosition, 6) << ","
             << "\"tsn\":" << timeSigNum << ","
             << "\"tsd\":" << timeSigDen
             << "}";
        return json;
    }
};
