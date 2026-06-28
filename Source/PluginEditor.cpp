#include "PluginProcessor.h"
#include "PluginEditor.h"

// ─────────────────────────────────────────────────────────────────────────────
// INFO POPUP OVERLAY COMPONENT
// ─────────────────────────────────────────────────────────────────────────────

InfoPopupComponent::InfoPopupComponent()
{
    logoImage = juce::ImageCache::getFromMemory(BinaryData::logobkr_png, BinaryData::logobkr_pngSize);
}

void InfoPopupComponent::paint(juce::Graphics& g)
{
    // 1. Semi-transparent dark overlay
    g.fillAll(juce::Colours::black.withAlpha(0.88f));

    // 2. Centred dialog box
    int boxW = 360;
    int boxH = 260;
    int boxX = (getWidth()  - boxW) / 2;
    int boxY = (getHeight() - boxH) / 2;

    juce::Path borderPath;
    borderPath.addRoundedRectangle(boxX, boxY, boxW, boxH, 10.0f);

    g.setColour(juce::Colour(0xFF0A140A));
    g.fillPath(borderPath);

    g.setColour(juce::Colour(0xFF39FF14));
    g.strokePath(borderPath, juce::PathStrokeType(2.0f));

    // 3. BKR Logo
    if (logoImage.isValid())
    {
        int imgW = 140;
        int imgH = (logoImage.getHeight() * imgW) / logoImage.getWidth();
        int imgX = boxX + (boxW - imgW) / 2;
        int imgY = boxY + 14;
        g.drawImageWithin(logoImage, imgX, imgY, imgW, imgH, juce::RectanglePlacement::centred);
    }

    // 4. Product title
    g.setColour(juce::Colour(0xFFE5FFE8));
    g.setFont(juce::Font(juce::Font::getDefaultMonospacedFontName(), 15.0f, juce::Font::bold));
    g.drawText("BKR Bus Bridge", boxX, boxY + 90, boxW, 22, juce::Justification::centred);

    // 5. Version
    g.setColour(juce::Colour(0xFF39FF14));
    g.setFont(juce::Font(juce::Font::getDefaultMonospacedFontName(), 11.0f, juce::Font::plain));
    g.drawText("v1.1", boxX, boxY + 112, boxW, 16, juce::Justification::centred);

    // 6. Divider
    g.setColour(juce::Colour(0xFF39FF14).withAlpha(0.35f));
    g.drawLine((float)(boxX + 20), (float)(boxY + 134), (float)(boxX + boxW - 20), (float)(boxY + 134), 1.0f);

    // 7. Creator / company
    g.setColour(juce::Colour(0xFF7CFF6B));
    g.setFont(juce::Font(juce::Font::getDefaultMonospacedFontName(), 11.0f, juce::Font::plain));
    g.drawFittedText("Designed by Anderson Guerra\nZombiePhone FX  |  Copyright \u00A9 2026",
                      boxX + 10, boxY + 142, boxW - 20, 36,
                      juce::Justification::centred, 2);

    // 8. Info links
    g.setColour(juce::Colour(0xFF39FF14));
    g.setFont(juce::Font(juce::Font::getDefaultMonospacedFontName(), 10.0f, juce::Font::plain));
    g.drawFittedText("zombiephonefx.com  |  bkr.studio",
                      boxX + 10, boxY + 186, boxW - 20, 16,
                      juce::Justification::centred, 1);

    // 9. Tap-to-dismiss hint
    g.setColour(juce::Colour(0xFF004D00));
    g.setFont(juce::Font(juce::FontOptions(9.0f)));
    g.drawText("[ tap to close ]", boxX, boxY + boxH - 18, boxW, 14, juce::Justification::centred);
}

void InfoPopupComponent::mouseDown(const juce::MouseEvent&)
{
    setVisible(false);
}

// ─────────────────────────────────────────────────────────────────────────────
// MAIN PLUG-IN EDITOR  –  BKR Bus Bridge
// Layout: 840 x 480  |  two side panels over bridge image
// ─────────────────────────────────────────────────────────────────────────────

static constexpr int W = 840;
static constexpr int H = 480;

// Panel geometry (left / right arch areas)
static constexpr int PW   = 220;   // panel width
static constexpr int PH   = 168;   // panel height
static constexpr int PADX = 18;    // horizontal margin from edge
static constexpr int PY   = H - PH - 18; // top-y of both panels

MasterStreamerAudioProcessorEditor::MasterStreamerAudioProcessorEditor(MasterStreamerAudioProcessor& p)
    : AudioProcessorEditor(&p), audioProcessor(p)
{
    setSize(W, H);

    // ── Phosphor palette ────────────────────────────────────────────────────
    auto phosphorGreen = juce::Colour(0xFF39FF14);
    auto phosphorText  = juce::Colour(0xFF7CFF6B);
    auto phosphorDim   = juce::Colour(0xFF004D00);
    auto phosphorBg    = juce::Colour(0xFF030803);

    // ── Helper lambdas ──────────────────────────────────────────────────────
    auto applyInputStyle = [&](juce::TextEditor& ed) {
        ed.setColour(juce::TextEditor::backgroundColourId,     phosphorBg);
        ed.setColour(juce::TextEditor::textColourId,           phosphorGreen);
        ed.setColour(juce::TextEditor::outlineColourId,        phosphorDim);
        ed.setColour(juce::TextEditor::focusedOutlineColourId, phosphorGreen);
        ed.setFont(juce::Font(juce::Font::getDefaultMonospacedFontName(), 12.0f, juce::Font::plain));
    };

    auto applyLabelStyle = [&](juce::Label& lbl, float sz, bool bold) {
        lbl.setColour(juce::Label::textColourId, phosphorText);
        lbl.setFont(juce::Font(juce::Font::getDefaultMonospacedFontName(), sz,
                               bold ? juce::Font::bold : juce::Font::plain));
    };

    auto applyToggleStyle = [&](juce::ToggleButton& btn) {
        btn.setColour(juce::ToggleButton::textColourId,        phosphorText);
        btn.setColour(juce::ToggleButton::tickColourId,        phosphorGreen);
        btn.setColour(juce::ToggleButton::tickDisabledColourId, juce::Colour(0xFF004D00));
    };

    // =========================================================================
    // AUDIO PANEL
    // =========================================================================
    audioTitleLabel.setText("AUDIO STREAM", juce::dontSendNotification);
    applyLabelStyle(audioTitleLabel, 11.0f, true);
    addAndMakeVisible(audioTitleLabel);

    ipLabel.setText("IP:", juce::dontSendNotification);
    applyLabelStyle(ipLabel, 10.0f, false);
    addAndMakeVisible(ipLabel);

    ipInputField.setText(audioProcessor.targetIpAddress, false);
    applyInputStyle(ipInputField);
    ipInputField.onTextChange = [this]() {
        audioProcessor.targetIpAddress = ipInputField.getText();
        audioProcessor.getSender().configure(audioProcessor.targetIpAddress, audioProcessor.targetPortNumber);
    };
    addAndMakeVisible(ipInputField);

    portLabel.setText("PORT:", juce::dontSendNotification);
    applyLabelStyle(portLabel, 10.0f, false);
    addAndMakeVisible(portLabel);

    portInputField.setText(juce::String(audioProcessor.targetPortNumber), false);
    applyInputStyle(portInputField);
    portInputField.setInputRestrictions(5, "0123456789");
    portInputField.onTextChange = [this]() {
        int p2 = portInputField.getText().getIntValue();
        audioProcessor.targetPortNumber = juce::jlimit(1024, 65535, p2);
        audioProcessor.getSender().configure(audioProcessor.targetIpAddress, audioProcessor.targetPortNumber);
    };
    addAndMakeVisible(portInputField);

    streamToggleButton.setButtonText("ACTIVATE");
    applyToggleStyle(streamToggleButton);
    streamToggleButton.setToggleState(audioProcessor.isStreamingEnabled, juce::dontSendNotification);
    streamToggleButton.onClick = [this]() {
        bool on = streamToggleButton.getToggleState();
        audioProcessor.isStreamingEnabled = on;
        audioProcessor.getSender().setEnabled(on);
    };
    addAndMakeVisible(streamToggleButton);

    packetCounterLabel.setText("PKT: 0 packets", juce::dontSendNotification);
    applyLabelStyle(packetCounterLabel, 9.0f, false);
    addAndMakeVisible(packetCounterLabel);

    infoLabel.setText("", juce::dontSendNotification);
    applyLabelStyle(infoLabel, 9.0f, false);
    addAndMakeVisible(infoLabel);

    // =========================================================================
    // BPM / TIMECODE / TALLY PANEL
    // =========================================================================
    transportTitleLabel.setText("BPM, TIMECODE & TALLY", juce::dontSendNotification);
    applyLabelStyle(transportTitleLabel, 10.0f, true);
    addAndMakeVisible(transportTitleLabel);

    transportSubtitleLabel.setText("CONFIGURATION", juce::dontSendNotification);
    applyLabelStyle(transportSubtitleLabel, 10.0f, true);
    addAndMakeVisible(transportSubtitleLabel);

    transportIpLabel.setText("IP:", juce::dontSendNotification);
    applyLabelStyle(transportIpLabel, 10.0f, false);
    addAndMakeVisible(transportIpLabel);

    transportIpInputField.setText(audioProcessor.transportIpAddress, false);
    applyInputStyle(transportIpInputField);
    transportIpInputField.onTextChange = [this]() {
        audioProcessor.transportIpAddress = transportIpInputField.getText();
        audioProcessor.getTransportSender().configure(audioProcessor.transportIpAddress, audioProcessor.transportPortNumber);
    };
    addAndMakeVisible(transportIpInputField);

    transportPortLabel.setText("PORT:", juce::dontSendNotification);
    applyLabelStyle(transportPortLabel, 10.0f, false);
    addAndMakeVisible(transportPortLabel);

    transportPortInputField.setText(juce::String(audioProcessor.transportPortNumber), false);
    applyInputStyle(transportPortInputField);
    transportPortInputField.setInputRestrictions(5, "0123456789");
    transportPortInputField.onTextChange = [this]() {
        int p2 = transportPortInputField.getText().getIntValue();
        audioProcessor.transportPortNumber = juce::jlimit(1024, 65535, p2);
        audioProcessor.getTransportSender().configure(audioProcessor.transportIpAddress, audioProcessor.transportPortNumber);
    };
    addAndMakeVisible(transportPortInputField);

    transportToggleButton.setButtonText("ACTIVATE");
    applyToggleStyle(transportToggleButton);
    transportToggleButton.setToggleState(audioProcessor.isTransportStreamingEnabled, juce::dontSendNotification);
    transportToggleButton.onClick = [this]() {
        bool on = transportToggleButton.getToggleState();
        audioProcessor.isTransportStreamingEnabled = on;
        audioProcessor.getTransportSender().setEnabled(on);
    };
    addAndMakeVisible(transportToggleButton);

    transportPacketCounterLabel.setText("PKT: 0 packets", juce::dontSendNotification);
    applyLabelStyle(transportPacketCounterLabel, 9.0f, false);
    addAndMakeVisible(transportPacketCounterLabel);

    transportInfoLabel.setText("", juce::dontSendNotification);
    applyLabelStyle(transportInfoLabel, 9.0f, false);
    addAndMakeVisible(transportInfoLabel);

    // =========================================================================
    // INFO BUTTON + POPUP
    // =========================================================================
    infoButton.setButtonText("i");
    infoButton.setColour(juce::TextButton::buttonColourId,   juce::Colours::transparentBlack);
    infoButton.setColour(juce::TextButton::buttonOnColourId, juce::Colours::transparentBlack);
    infoButton.setColour(juce::TextButton::textColourOffId,  phosphorText);
    infoButton.setColour(juce::TextButton::textColourOnId,   phosphorGreen);
    infoButton.onClick = [this]() {
        infoPopup.setVisible(true);
        infoPopup.toFront(true);
    };
    addAndMakeVisible(infoButton);

    addChildComponent(infoPopup);
    infoPopup.setVisible(false);

    startTimerHz(15);
}

MasterStreamerAudioProcessorEditor::~MasterStreamerAudioProcessorEditor() {}

// ─────────────────────────────────────────────────────────────────────────────
// paint  –  draw background image + two frosted phosphor panels
// ─────────────────────────────────────────────────────────────────────────────
void MasterStreamerAudioProcessorEditor::paint(juce::Graphics& g)
{
    // 1. Draw bus-bridge background image
    auto bg = juce::ImageCache::getFromMemory(BinaryData::busbridge_jpg, BinaryData::busbridge_jpgSize);
    if (bg.isValid())
        g.drawImageWithin(bg, 0, 0, getWidth(), getHeight(), juce::RectanglePlacement::fillDestination);
    else
        g.fillAll(juce::Colour(0xFF030803));

    // 2. Left panel (AUDIO STREAM)
    {
        juce::Path p;
        p.addRoundedRectangle(PADX, PY, PW, PH, 8.0f);
        g.setColour(juce::Colour(0xFF0A140A).withAlpha(0.86f));
        g.fillPath(p);
        // Glow border — three passes
        for (auto [offset, alpha] : { std::pair{2,40}, {1,110}, {0,230} })
        {
            juce::Path bp;
            bp.addRoundedRectangle(PADX - offset, PY - offset,
                                   PW + offset * 2, PH + offset * 2, 8.0f + offset);
            g.setColour(juce::Colour(0xFF39FF14).withAlpha((uint8_t)alpha));
            g.strokePath(bp, juce::PathStrokeType(1.0f));
        }
    }

    // 3. Right panel (BPM / TIMECODE / TALLY)
    {
        int rx = getWidth() - PW - PADX;
        juce::Path p;
        p.addRoundedRectangle(rx, PY, PW, PH, 8.0f);
        g.setColour(juce::Colour(0xFF0A140A).withAlpha(0.86f));
        g.fillPath(p);
        for (auto [offset, alpha] : { std::pair{2,40}, {1,110}, {0,230} })
        {
            juce::Path bp;
            bp.addRoundedRectangle(rx - offset, PY - offset,
                                   PW + offset * 2, PH + offset * 2, 8.0f + offset);
            g.setColour(juce::Colour(0xFF39FF14).withAlpha((uint8_t)alpha));
            g.strokePath(bp, juce::PathStrokeType(1.0f));
        }
    }

    // 4. Panel divider lines (below title)
    g.setColour(juce::Colour(0xFF39FF14).withAlpha(0.3f));
    g.drawLine(PADX + 8, PY + 24, PADX + PW - 8, PY + 24, 1.0f);
    int rx2 = getWidth() - PW - PADX;
    g.drawLine(rx2 + 8, PY + 36, rx2 + PW - 8, PY + 36, 1.0f);  // right panel has 2-line title → lower

    // 5. Version tag bottom-centre
    g.setColour(juce::Colour(0xFF004400));
    g.setFont(juce::Font(juce::FontOptions(9.0f)));
    g.drawText("BKR BB v1.1", 0, getHeight() - 14, getWidth(), 12, juce::Justification::centred);
}

// ─────────────────────────────────────────────────────────────────────────────
// resized  –  position all child components inside the panels
// ─────────────────────────────────────────────────────────────────────────────
void MasterStreamerAudioProcessorEditor::resized()
{
    // Info button – top right
    infoButton.setBounds(getWidth() - 28, 8, 20, 20);

    // Popup covers full editor
    infoPopup.setBounds(getLocalBounds());

    // ── Left panel (Audio) ────────────────────────────────────────────
    int lx = PADX + 6;
    int fw = PW - 12;   // field width

    audioTitleLabel.setBounds(lx, PY + 5, fw, 16);

    // IP row
    ipLabel.setBounds(lx, PY + 30, 38, 20);
    ipInputField.setBounds(lx + 40, PY + 30, fw - 40, 20);

    // PORT row
    portLabel.setBounds(lx, PY + 56, 38, 20);
    portInputField.setBounds(lx + 40, PY + 56, fw - 40, 20);

    // Toggle
    streamToggleButton.setBounds(lx, PY + 84, fw, 22);

    // PKT counter
    packetCounterLabel.setBounds(lx, PY + 114, fw, 16);
    infoLabel.setBounds(lx, PY + 130, fw, 14);

    // ── Right panel (BPM / Timecode / Tally) ─────────────────────────
    int rx = getWidth() - PW - PADX + 6;

    // Two-line title: stacked labels
    transportTitleLabel.setBounds(rx, PY + 4, PW - 12, 14);
    transportSubtitleLabel.setBounds(rx, PY + 19, PW - 12, 14);

    transportIpLabel.setBounds(rx, PY + 43, 38, 20);
    transportIpInputField.setBounds(rx + 40, PY + 43, PW - 12 - 40, 20);

    transportPortLabel.setBounds(rx, PY + 69, 38, 20);
    transportPortInputField.setBounds(rx + 40, PY + 69, PW - 12 - 40, 20);

    transportToggleButton.setBounds(rx, PY + 97, PW - 12, 22);

    transportPacketCounterLabel.setBounds(rx, PY + 127, PW - 12, 16);
    transportInfoLabel.setBounds(rx, PY + 143, PW - 12, 14);
}

// ─────────────────────────────────────────────────────────────────────────────
// timerCallback  –  refresh live stats labels
// ─────────────────────────────────────────────────────────────────────────────
void MasterStreamerAudioProcessorEditor::timerCallback()
{
    // Audio stream
    auto& audioSender   = audioProcessor.getSender();
    bool  isAudioOn     = audioSender.isStreamingActive();
    uint32_t audioPkts  = audioSender.getPacketCount();

    if (isAudioOn)
    {
        packetCounterLabel.setText("PKT: " + juce::String(audioPkts) + " packets", juce::dontSendNotification);
        packetCounterLabel.setColour(juce::Label::textColourId, juce::Colours::lightgreen);
        infoLabel.setText("Stereo PCM 16-bit", juce::dontSendNotification);
        infoLabel.setColour(juce::Label::textColourId, juce::Colour(0xFF7CFF6B));
    }
    else
    {
        packetCounterLabel.setText("PKT: inactive", juce::dontSendNotification);
        packetCounterLabel.setColour(juce::Label::textColourId, juce::Colour(0xFF005500));
        infoLabel.setText("", juce::dontSendNotification);
    }

    // Transport / timecode stream
    auto& transportSender = audioProcessor.getTransportSender();
    bool  isTransportOn   = transportSender.isStreamingActive();
    uint32_t transPkts    = transportSender.getPacketCount();

    if (isTransportOn)
    {
        transportPacketCounterLabel.setText("PKT: " + juce::String(transPkts) + " packets", juce::dontSendNotification);
        transportPacketCounterLabel.setColour(juce::Label::textColourId, juce::Colours::lightgreen);
        transportInfoLabel.setText("TC + BPM @ 30Hz", juce::dontSendNotification);
        transportInfoLabel.setColour(juce::Label::textColourId, juce::Colour(0xFF7CFF6B));
    }
    else
    {
        transportPacketCounterLabel.setText("PKT: inactive", juce::dontSendNotification);
        transportPacketCounterLabel.setColour(juce::Label::textColourId, juce::Colour(0xFF005500));
        transportInfoLabel.setText("", juce::dontSendNotification);
    }
}
