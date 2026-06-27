#include "PluginProcessor.h"
#include "PluginEditor.h"

// -----------------------------------------------------------------------------
// INFO POPUP OVERLAY COMPONENT
// -----------------------------------------------------------------------------

InfoPopupComponent::InfoPopupComponent()
{
    logoImage = juce::ImageCache::getFromMemory(BinaryData::logobkr_png, BinaryData::logobkr_pngSize);
}

void InfoPopupComponent::paint(juce::Graphics& g)
{
    // 1. Semi-transparent dark overlay covering the whole editor
    g.fillAll(juce::Colours::black.withAlpha(0.82f));

    // 2. Centered dialog box
    int boxW = 320;
    int boxH = 180;
    int boxX = (getWidth() - boxW) / 2;
    int boxY = (getHeight() - boxH) / 2;

    juce::Path borderPath;
    borderPath.addRoundedRectangle(boxX, boxY, boxW, boxH, 8.0f);

    // Fill background (PhosphorSurface: 0xFF0A140A)
    g.setColour(juce::Colour(0xFF0A140A));
    g.fillPath(borderPath);

    // Draw bright outline border (PhosphorGreen: 0xFF39FF14)
    g.setColour(juce::Colour(0xFF39FF14));
    g.strokePath(borderPath, juce::PathStrokeType(2.0f));

    // 3. Draw BKR Logo inside the popup box
    if (logoImage.isValid())
    {
        int imgW = 160;
        int imgH = (logoImage.getHeight() * imgW) / logoImage.getWidth();
        int imgX = boxX + (boxW - imgW) / 2;
        int imgY = boxY + 20;

        g.drawImageWithin(logoImage, imgX, imgY, imgW, imgH, juce::RectanglePlacement::centred);
    }

    // 4. Draw Product Title: "MasterStreamer" (PhosphorHigh: 0xFFE5FFE8)
    g.setColour(juce::Colour(0xFFE5FFE8));
    g.setFont(juce::Font(juce::Font::getDefaultMonospacedFontName(), 14.0f, juce::Font::bold));
    g.drawText("MasterStreamer", boxX, boxY + 85, boxW, 20, juce::Justification::centred);

    // 5. Draw Creator Details (PhosphorText: 0xFF7CFF6B)
    g.setColour(juce::Colour(0xFF7CFF6B));
    g.setFont(juce::Font(juce::Font::getDefaultMonospacedFontName(), 11.0f, juce::Font::plain));
    g.drawFittedText("Designed by Anderson Guerra / ZombiePhone FX\nCopyright \u00A9 2026", 
                      boxX + 10, boxY + 115, boxW - 20, 45, 
                      juce::Justification::centred, 2);
}

void InfoPopupComponent::mouseDown(const juce::MouseEvent& event)
{
    setVisible(false);
}

// -----------------------------------------------------------------------------
// MAIN PLUG-IN EDITOR COMPONENT
// -----------------------------------------------------------------------------

MasterStreamerAudioProcessorEditor::MasterStreamerAudioProcessorEditor(MasterStreamerAudioProcessor& p)
    : AudioProcessorEditor(&p), audioProcessor(p)
{
    setSize(450, 470);

    // Color definitions based on Android theme
    auto phosphorGreen = juce::Colour(0xFF39FF14);
    auto phosphorText = juce::Colour(0xFF7CFF6B);
    auto phosphorDim = juce::Colour(0xFF008800);
    auto phosphorBackground = juce::Colour(0xFF030803);

    // Lambda helper to style text inputs
    auto applyInputStyle = [&](juce::TextEditor& editor) {
        editor.setColour(juce::TextEditor::backgroundColourId, phosphorBackground);
        editor.setColour(juce::TextEditor::textColourId, phosphorGreen);
        editor.setColour(juce::TextEditor::outlineColourId, phosphorDim);
        editor.setColour(juce::TextEditor::focusedOutlineColourId, phosphorGreen);
        editor.setFont(juce::Font(juce::Font::getDefaultMonospacedFontName(), 13.0f, juce::Font::plain));
    };

    // Lambda helper to style labels
    auto applyLabelStyle = [&](juce::Label& label, float fontSize, bool isBold) {
        label.setColour(juce::Label::textColourId, phosphorText);
        label.setFont(juce::Font(juce::Font::getDefaultMonospacedFontName(), fontSize, isBold ? juce::Font::bold : juce::Font::plain));
    };

    // Lambda helper to style toggles
    auto applyToggleStyle = [&](juce::ToggleButton& btn) {
        btn.setColour(juce::ToggleButton::textColourId, phosphorText);
        btn.setColour(juce::ToggleButton::tickColourId, phosphorGreen);
        btn.setColour(juce::ToggleButton::tickDisabledColourId, juce::Colour(0xFF004D00));
    };

    // =========================================================================
    // AUDIO PANEL SETUP
    // =========================================================================
    audioTitleLabel.setText("AUDIO STREAM CONFIGURATION", juce::dontSendNotification);
    applyLabelStyle(audioTitleLabel, 12.0f, true);
    addAndMakeVisible(audioTitleLabel);

    ipLabel.setText("Android IP Address:", juce::dontSendNotification);
    applyLabelStyle(ipLabel, 11.0f, false);
    addAndMakeVisible(ipLabel);
    
    ipInputField.setText(audioProcessor.targetIpAddress, false);
    applyInputStyle(ipInputField);
    ipInputField.onTextChange = [this]() {
         audioProcessor.targetIpAddress = ipInputField.getText();
         audioProcessor.getSender().configure(audioProcessor.targetIpAddress, audioProcessor.targetPortNumber);
    };
    addAndMakeVisible(ipInputField);

    portLabel.setText("UDP Audio Port:", juce::dontSendNotification);
    applyLabelStyle(portLabel, 11.0f, false);
    addAndMakeVisible(portLabel);
    
    portInputField.setText(juce::String(audioProcessor.targetPortNumber), false);
    applyInputStyle(portInputField);
    portInputField.setInputRestrictions(5, "0123456789");
    portInputField.onTextChange = [this]() {
         int inputPort = portInputField.getText().getIntValue();
         audioProcessor.targetPortNumber = juce::jlimit(1024, 65535, inputPort);
         audioProcessor.getSender().configure(audioProcessor.targetIpAddress, audioProcessor.targetPortNumber);
    };
    addAndMakeVisible(portInputField);

    streamToggleButton.setButtonText("ACTIVATE UDP AUDIO STREAM");
    applyToggleStyle(streamToggleButton);
    streamToggleButton.setToggleState(audioProcessor.isStreamingEnabled, juce::dontSendNotification);
    streamToggleButton.onClick = [this]() {
        bool isActive = streamToggleButton.getToggleState();
        audioProcessor.isStreamingEnabled = isActive;
        audioProcessor.getSender().setEnabled(isActive);
    };
    addAndMakeVisible(streamToggleButton);

    packetCounterLabel.setText("Dispatched: Stream Inactive", juce::dontSendNotification);
    applyLabelStyle(packetCounterLabel, 11.0f, true);
    addAndMakeVisible(packetCounterLabel);

    infoLabel.setText("State: Sleeping", juce::dontSendNotification);
    applyLabelStyle(infoLabel, 10.0f, false);
    addAndMakeVisible(infoLabel);

    // =========================================================================
    // TIMECODE PANEL SETUP
    // =========================================================================
    transportTitleLabel.setText("TIMECODE & TALLY CONFIGURATION", juce::dontSendNotification);
    applyLabelStyle(transportTitleLabel, 12.0f, true);
    addAndMakeVisible(transportTitleLabel);

    transportIpLabel.setText("Android IP Address:", juce::dontSendNotification);
    applyLabelStyle(transportIpLabel, 11.0f, false);
    addAndMakeVisible(transportIpLabel);
    
    transportIpInputField.setText(audioProcessor.transportIpAddress, false);
    applyInputStyle(transportIpInputField);
    transportIpInputField.onTextChange = [this]() {
         audioProcessor.transportIpAddress = transportIpInputField.getText();
         audioProcessor.getTransportSender().configure(audioProcessor.transportIpAddress, audioProcessor.transportPortNumber);
    };
    addAndMakeVisible(transportIpInputField);

    transportPortLabel.setText("UDP Timecode Port:", juce::dontSendNotification);
    applyLabelStyle(transportPortLabel, 11.0f, false);
    addAndMakeVisible(transportPortLabel);
    
    transportPortInputField.setText(juce::String(audioProcessor.transportPortNumber), false);
    applyInputStyle(transportPortInputField);
    transportPortInputField.setInputRestrictions(5, "0123456789");
    transportPortInputField.onTextChange = [this]() {
         int inputPort = transportPortInputField.getText().getIntValue();
         audioProcessor.transportPortNumber = juce::jlimit(1024, 65535, inputPort);
         audioProcessor.getTransportSender().configure(audioProcessor.transportIpAddress, audioProcessor.transportPortNumber);
    };
    addAndMakeVisible(transportPortInputField);

    transportToggleButton.setButtonText("ACTIVATE UDP TIMECODE STREAM");
    applyToggleStyle(transportToggleButton);
    transportToggleButton.setToggleState(audioProcessor.isTransportStreamingEnabled, juce::dontSendNotification);
    transportToggleButton.onClick = [this]() {
        bool isActive = transportToggleButton.getToggleState();
        audioProcessor.isTransportStreamingEnabled = isActive;
        audioProcessor.getTransportSender().setEnabled(isActive);
    };
    addAndMakeVisible(transportToggleButton);

    transportPacketCounterLabel.setText("Dispatched: Stream Inactive", juce::dontSendNotification);
    applyLabelStyle(transportPacketCounterLabel, 11.0f, true);
    addAndMakeVisible(transportPacketCounterLabel);

    transportInfoLabel.setText("State: Sleeping", juce::dontSendNotification);
    applyLabelStyle(transportInfoLabel, 10.0f, false);
    addAndMakeVisible(transportInfoLabel);

    // =========================================================================
    // INFO WINDOW BUTTON AND MODAL POPUP
    // =========================================================================
    infoButton.setButtonText("i");
    infoButton.setColour(juce::TextButton::buttonColourId, juce::Colours::transparentBlack);
    infoButton.setColour(juce::TextButton::buttonOnColourId, juce::Colours::transparentBlack);
    infoButton.setColour(juce::TextButton::textColourOffId, phosphorText);
    infoButton.setColour(juce::TextButton::textColourOnId, phosphorGreen);
    infoButton.onClick = [this]() {
        infoPopup.setVisible(true);
        infoPopup.toFront(true);
    };
    addAndMakeVisible(infoButton);

    // Add infoPopup as overlay (starts invisible)
    addChildComponent(infoPopup);
    infoPopup.setVisible(false);

    startTimerHz(15);
}

MasterStreamerAudioProcessorEditor::~MasterStreamerAudioProcessorEditor()
{
}

void MasterStreamerAudioProcessorEditor::paint(juce::Graphics& g)
{
    // BKR Phosphor dark background
    g.fillAll(juce::Colour(0xFF030803));

    // Outer glowing border
    g.setColour(juce::Colour(0xFF008800));
    g.drawRect(getLocalBounds(), 2);

    // Section 1 (Audio Panel) Background & Outline
    g.setColour(juce::Colour(0xFF0A140A));
    juce::Path p1;
    p1.addRoundedRectangle(15, 75, getWidth() - 30, 165, 6.0f);
    g.fillPath(p1);
    g.setColour(juce::Colour(0xFF004D00));
    g.strokePath(p1, juce::PathStrokeType(1.0f));

    // Section 2 (Timecode Panel) Background & Outline
    g.setColour(juce::Colour(0xFF0A140A));
    juce::Path p2;
    p2.addRoundedRectangle(15, 255, getWidth() - 30, 165, 6.0f);
    g.fillPath(p2);
    g.setColour(juce::Colour(0xFF004D00));
    g.strokePath(p2, juce::PathStrokeType(1.0f));

    // Draw centered BKR header logo
    auto logo = juce::ImageCache::getFromMemory(BinaryData::logobkr_png, BinaryData::logobkr_pngSize);
    if (logo.isValid())
    {
        int logoW = 176;
        int logoH = (logo.getHeight() * logoW) / logo.getWidth();
        int logoX = (getWidth() - logoW) / 2;
        int logoY = 8;
        g.drawImageWithin(logo, logoX, logoY, logoW, logoH, juce::RectanglePlacement::centred);
    }

    // Draw version string in bottom-left corner
    g.setColour(juce::Colour(0xFF005500));
    g.setFont(juce::Font (juce::FontOptions (9.0f)));
    g.drawText("BKRBPM v1.1", 15, getHeight() - 20, 150, 12, juce::Justification::left);
}

void MasterStreamerAudioProcessorEditor::resized()
{
    // Position Info Button top-right
    infoButton.setBounds(getWidth() - 35, 15, 20, 20);

    // Info overlay covers full editor bounds
    infoPopup.setBounds(getLocalBounds());

    int labelWidth = 140;
    int fieldWidth = 240;

    // =========================================================================
    // AUDIO PANEL POSITIONING
    // =========================================================================
    audioTitleLabel.setBounds(25, 85, 250, 16);
    
    ipLabel.setBounds(30, 110, labelWidth, 24);
    ipInputField.setBounds(30 + labelWidth, 110, fieldWidth, 24);

    portLabel.setBounds(30, 140, labelWidth, 24);
    portInputField.setBounds(30 + labelWidth, 140, fieldWidth, 24);

    streamToggleButton.setBounds(30, 175, getWidth() - 60, 24);

    packetCounterLabel.setBounds(30, 210, 200, 20);
    infoLabel.setBounds(240, 210, 180, 20);

    // =========================================================================
    // TIMECODE PANEL POSITIONING
    // =========================================================================
    transportTitleLabel.setBounds(25, 265, 250, 16);

    transportIpLabel.setBounds(30, 290, labelWidth, 24);
    transportIpInputField.setBounds(30 + labelWidth, 290, fieldWidth, 24);

    transportPortLabel.setBounds(30, 320, labelWidth, 24);
    transportPortInputField.setBounds(30 + labelWidth, 320, fieldWidth, 24);

    transportToggleButton.setBounds(30, 355, getWidth() - 60, 24);

    transportPacketCounterLabel.setBounds(30, 390, 200, 20);
    transportInfoLabel.setBounds(240, 390, 180, 20);
}

void MasterStreamerAudioProcessorEditor::timerCallback()
{
    // 1. Update Audio Stats
    auto& audioSender = audioProcessor.getSender();
    bool isAudioStreaming = audioSender.isStreamingActive();
    uint32_t audioPackets = audioSender.getPacketCount();

    if (isAudioStreaming)
    {
        packetCounterLabel.setText("Dispatched: " + juce::String(audioPackets) + " packets", juce::dontSendNotification);
        packetCounterLabel.setColour(juce::Label::textColourId, juce::Colours::lightgreen);
        infoLabel.setText("Streaming: Stereo PCM 16-bit", juce::dontSendNotification);
        infoLabel.setColour(juce::Label::textColourId, juce::Colours::lightgrey);
    }
    else
    {
        packetCounterLabel.setText("Dispatched: Stream Inactive", juce::dontSendNotification);
        packetCounterLabel.setColour(juce::Label::textColourId, juce::Colours::darkgrey);
        infoLabel.setText("State: Sleeping", juce::dontSendNotification);
        infoLabel.setColour(juce::Label::textColourId, juce::Colours::darkgrey);
    }

    // 2. Update Timecode Stats
    auto& transportSender = audioProcessor.getTransportSender();
    bool isTransportStreaming = transportSender.isStreamingActive();
    uint32_t transportPackets = transportSender.getPacketCount();

    if (isTransportStreaming)
    {
        transportPacketCounterLabel.setText("Dispatched: " + juce::String(transportPackets) + " packets", juce::dontSendNotification);
        transportPacketCounterLabel.setColour(juce::Label::textColourId, juce::Colours::lightgreen);
        transportInfoLabel.setText("Streaming: Timecode (30Hz)", juce::dontSendNotification);
        transportInfoLabel.setColour(juce::Label::textColourId, juce::Colours::lightgrey);
    }
    else
    {
        transportPacketCounterLabel.setText("Dispatched: Stream Inactive", juce::dontSendNotification);
        transportPacketCounterLabel.setColour(juce::Label::textColourId, juce::Colours::darkgrey);
        transportInfoLabel.setText("State: Sleeping", juce::dontSendNotification);
        transportInfoLabel.setColour(juce::Label::textColourId, juce::Colours::darkgrey);
    }
}
