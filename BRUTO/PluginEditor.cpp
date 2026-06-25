#include "PluginEditor.h"

ClarityMeterSenderEditor::ClarityMeterSenderEditor (
    ClarityMeterSenderProcessor& p)
    : AudioProcessorEditor (&p),
      audioProcessor (p)
{
    setSize (420, 180);

    addAndMakeVisible (ipLabel);
    ipLabel.setText ("TARGET IP", juce::dontSendNotification);

    addAndMakeVisible (ipEditor);
    ipEditor.setText (audioProcessor.getTargetIP());

    ipEditor.onTextChange = [this]
    {
        audioProcessor.setTargetIP (ipEditor.getText());
    };

    addAndMakeVisible (portLabel);
    portLabel.setText ("PORT", juce::dontSendNotification);

    addAndMakeVisible (portEditor);
    portEditor.setText (
        juce::String (audioProcessor.getPort()));

    portEditor.onTextChange = [this]
    {
        audioProcessor.setPort (
            portEditor.getText().getIntValue());
    };

    addAndMakeVisible (enableButton);

    enableButton.setButtonText ("ENABLE UDP");

    enableButton.setToggleState (
        audioProcessor.isEnabled(),
        juce::dontSendNotification);

    enableButton.onClick = [this]
    {
        audioProcessor.setEnabled (
            enableButton.getToggleState());
    };

    startTimerHz (20);
}

ClarityMeterSenderEditor::~ClarityMeterSenderEditor()
{
}

void ClarityMeterSenderEditor::paint (juce::Graphics& g)
{
    g.fillAll (juce::Colours::black);

    g.setColour (juce::Colours::white);

    g.setFont (24.0f);

    g.drawFittedText (
        "MASTER STREAMER",
        0,
        10,
        getWidth(),
        30,
        juce::Justification::centred,
        1);
}

void ClarityMeterSenderEditor::resized()
{
    ipLabel.setBounds (20, 60, 100, 24);
    ipEditor.setBounds (120, 60, 200, 24);

    portLabel.setBounds (20, 95, 100, 24);
    portEditor.setBounds (120, 95, 100, 24);

    enableButton.setBounds (20, 130, 200, 24);
}

void ClarityMeterSenderEditor::timerCallback()
{
    repaint();
}