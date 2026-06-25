#pragma once

#include <JuceHeader.h>
#include "PluginProcessor.h"

class ClarityMeterSenderEditor
    : public juce::AudioProcessorEditor,
      private juce::Timer
{
public:

    ClarityMeterSenderEditor (ClarityMeterSenderProcessor&);
    ~ClarityMeterSenderEditor() override;

    void paint (juce::Graphics&) override;
    void resized() override;

private:

    void timerCallback() override;

    ClarityMeterSenderProcessor& audioProcessor;

    juce::Label ipLabel;
    juce::TextEditor ipEditor;

    juce::Label portLabel;
    juce::TextEditor portEditor;

    juce::ToggleButton enableButton;

    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR (ClarityMeterSenderEditor)
};