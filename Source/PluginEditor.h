#pragma once
#include <JuceHeader.h>
#include "PluginProcessor.h"

class InfoPopupComponent : public juce::Component
{
public:
    InfoPopupComponent();
    void paint(juce::Graphics&) override;
    void mouseDown(const juce::MouseEvent&) override;

private:
    juce::Image logoImage;
};

class MasterStreamerAudioProcessorEditor : public juce::AudioProcessorEditor,
                                           public juce::Timer
{
public:
    MasterStreamerAudioProcessorEditor(MasterStreamerAudioProcessor&);
    ~MasterStreamerAudioProcessorEditor() override;

    void paint(juce::Graphics&) override;
    void resized() override;
    void timerCallback() override;

private:
    MasterStreamerAudioProcessor& audioProcessor;

    // GUI UI Components - Audio Stream Section
    juce::Label audioTitleLabel;
    juce::TextEditor ipInputField;
    juce::Label ipLabel;
    juce::TextEditor portInputField;
    juce::Label portLabel;
    juce::ToggleButton streamToggleButton;
    juce::Label packetCounterLabel;
    juce::Label infoLabel;

    // GUI UI Components - Timecode / Tally Stream Section
    juce::Label transportTitleLabel;
    juce::Label transportSubtitleLabel;
    juce::TextEditor transportIpInputField;
    juce::Label transportIpLabel;
    juce::TextEditor transportPortInputField;
    juce::Label transportPortLabel;
    juce::ToggleButton transportToggleButton;
    juce::Label transportPacketCounterLabel;
    juce::Label transportInfoLabel;

    // Info Window Button and Overlay Popup
    juce::TextButton infoButton;
    InfoPopupComponent infoPopup;

    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR(MasterStreamerAudioProcessorEditor)
};
