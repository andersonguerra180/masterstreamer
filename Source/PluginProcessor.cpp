#include "PluginProcessor.h"
#include "PluginEditor.h"
#include <cmath>

// -----------------------------------------------------------------------------
// AUDIO SENDER THREAD IMPLEMENTATION
// -----------------------------------------------------------------------------

AudioSenderThread::AudioSenderThread() : Thread("MasterStreamerAudioSender")
{
    ringBuffer.setSize(2, 65536);
    ringBuffer.clear();
}

AudioSenderThread::~AudioSenderThread()
{
    stopThread(2000);
}

void AudioSenderThread::configure(const juce::String& targetIp, int targetPort)
{
    const juce::ScopedLock lock(configCriticalSection);
    ipAddress = targetIp;
    port = targetPort;
}

void AudioSenderThread::setEnabled(bool enable)
{
    streamingEnabled.store(enable);
}

void AudioSenderThread::pushAudio(const juce::AudioBuffer<float>& buffer)
{
    if (!streamingEnabled.load())
        return;

    int numSamples = buffer.getNumSamples();
    int numChannels = buffer.getNumChannels();
    
    int start1, size1, start2, size2;
    fifo.prepareToWrite(numSamples, start1, size1, start2, size2);
    
    int writeChannels = std::min(numChannels, 2);
    
    if (size1 > 0)
    {
        for (int ch = 0; ch < writeChannels; ++ch)
            ringBuffer.copyFrom(ch, start1, buffer, ch, 0, size1);
        if (writeChannels < 2)
            ringBuffer.copyFrom(1, start1, buffer, 0, 0, size1);
    }
    if (size2 > 0)
    {
        for (int ch = 0; ch < writeChannels; ++ch)
            ringBuffer.copyFrom(ch, start2, buffer, ch, size1, size2);
        if (writeChannels < 2)
            ringBuffer.copyFrom(1, start2, buffer, 0, size1, size2);
    }
    
    fifo.finishedWrite(size1 + size2);
}

void AudioSenderThread::prepare(double sampleRate, int maxBlockSize)
{
    currentSampleRate = sampleRate;
    ringBuffer.setSize(2, 65536);
    ringBuffer.clear();
    fifo.reset();
}

void AudioSenderThread::run()
{
    // Pass true to enable UDP Broadcasting
    juce::DatagramSocket socket(true);
    
    if (!socket.bindToPort(0))
    {
        juce::Logger::writeToLog("MASTERSTREAMER Error: Could not bind outbound UDP port.");
        return;
    }

    uint32_t currentSequence = 0;
    const int numSamplesToRead = 256;
    juce::AudioBuffer<float> tempBuffer(2, numSamplesToRead);
    AudioPacket packet;

    while (!threadShouldExit())
    {
        if (streamingEnabled.load())
        {
            int available = fifo.getNumReady();
            if (available >= numSamplesToRead)
            {
                int start1, size1, start2, size2;
                fifo.prepareToRead(numSamplesToRead, start1, size1, start2, size2);
                
                if (size1 > 0)
                {
                    tempBuffer.copyFrom(0, 0, ringBuffer, 0, start1, size1);
                    tempBuffer.copyFrom(1, 0, ringBuffer, 1, start1, size1);
                }
                if (size2 > 0)
                {
                    tempBuffer.copyFrom(0, size1, ringBuffer, 0, start2, size2);
                    tempBuffer.copyFrom(1, size1, ringBuffer, 1, start2, size2);
                }
                
                fifo.finishedRead(size1 + size2);

                packet.header.magic = 0x5541534D; // 'MSAU'
                packet.header.sequence = currentSequence++;
                packet.header.sampleRate = static_cast<uint32_t>(currentSampleRate);
                packet.header.numChannels = 2;
                packet.header.numSamples = numSamplesToRead;
                packet.header.bitDepth = 16;

                const float* leftData = tempBuffer.getReadPointer(0);
                const float* rightData = tempBuffer.getReadPointer(1);
                
                for (int i = 0; i < numSamplesToRead; ++i)
                {
                    packet.payload[i * 2]     = floatToPCM16(leftData[i]);
                    packet.payload[i * 2 + 1] = floatToPCM16(rightData[i]);
                }

                juce::String targetIp;
                int targetPort;
                {
                    const juce::ScopedLock lock(configCriticalSection);
                    targetIp = ipAddress;
                    targetPort = port;
                }

                socket.write(targetIp, targetPort, &packet, sizeof(packet));
                packetCount.store(currentSequence);
            }
            else
            {
                wait(1);
            }
        }
        else
        {
            wait(50);
        }
    }
}

// -----------------------------------------------------------------------------
// TRANSPORT SENDER THREAD IMPLEMENTATION
// -----------------------------------------------------------------------------

TransportSenderThread::TransportSenderThread() : Thread("MasterStreamerTransportSender")
{
}

TransportSenderThread::~TransportSenderThread()
{
    stopThread(2000);
}

void TransportSenderThread::configure(const juce::String& targetIp, int targetPort)
{
    const juce::ScopedLock lock(stateCriticalSection);
    ipAddress = targetIp;
    port = targetPort;
}

void TransportSenderThread::setEnabled(bool enable)
{
    streamingEnabled.store(enable);
}

void TransportSenderThread::updateState(const TransportState& newState)
{
    const juce::ScopedLock lock(stateCriticalSection);
    currentState = newState;
}

void TransportSenderThread::run()
{
    juce::DatagramSocket socket(true);
    
    if (!socket.bindToPort(0))
    {
        juce::Logger::writeToLog("MASTERSTREAMER Error: Could not bind outbound UDP transport port.");
        return;
    }

    uint32_t currentSequence = 0;

    while (!threadShouldExit())
    {
        if (streamingEnabled.load())
        {
            TransportState stateToSend;
            juce::String targetIp;
            int targetPort;

            {
                const juce::ScopedLock lock(stateCriticalSection);
                stateToSend = currentState;
                targetIp = ipAddress;
                targetPort = port;
            }

            juce::String json = stateToSend.toJson();
            socket.write(targetIp, targetPort, json.toRawUTF8(), (int) json.getNumBytesAsUTF8());
            packetCount.store(++currentSequence);
        }

        wait(33); // ~30Hz
    }
}

// -----------------------------------------------------------------------------
// MAIN AUDIO PROCESSOR DSP COMPONENT
// -----------------------------------------------------------------------------

MasterStreamerAudioProcessor::MasterStreamerAudioProcessor()
    : AudioProcessor(BusesProperties().withInput("Input", juce::AudioChannelSet::stereo(), true)
                                      .withOutput("Output", juce::AudioChannelSet::stereo(), true))
{
    // Start background threads immediately and keep them alive
    senderThread.startThread(Thread::Priority::normal);
    transportSenderThread.startThread(Thread::Priority::normal);
}

MasterStreamerAudioProcessor::~MasterStreamerAudioProcessor()
{
    senderThread.stopThread(2000);
    transportSenderThread.stopThread(2000);
}

void MasterStreamerAudioProcessor::prepareToPlay(double sr, int samplesPerBlock)
{
    senderThread.prepare(sr, samplesPerBlock);
    senderThread.configure(targetIpAddress, targetPortNumber);
    senderThread.setEnabled(isStreamingEnabled);

    transportSenderThread.configure(transportIpAddress, transportPortNumber);
    transportSenderThread.setEnabled(isTransportStreamingEnabled);
}

void MasterStreamerAudioProcessor::releaseResources()
{
    // Do NOT stop threads here! This avoids thread reconstruction on loop boundaries.
}

void MasterStreamerAudioProcessor::processBlock(juce::AudioBuffer<float>& buffer, juce::MidiBuffer& midiMessages)
{
    juce::ScopedNoDenormals noDenormals;
    const int numChannels = buffer.getNumChannels();
    const int numSamples = buffer.getNumSamples();

    // Bypass / Pass audio straight through untouched
    // (Ensure the DAW signal path is not broken)
    if (numChannels > 0 && numSamples > 0)
    {
        senderThread.pushAudio(buffer);
    }

    // --- Transport/Timecode broadcast (independent of audio stream) ---
    if (auto* playHead = getPlayHead())
    {
        if (auto position = playHead->getPosition())
        {
            TransportState state;
            state.isPlaying = position->getIsPlaying();
            state.isRecording = position->getIsRecording();
            
            double fps = 30.0;
            if (position->getFrameRate().hasValue())
                fps = position->getFrameRate()->getEffectiveRate();

            state.fps = fps;

            state.bpm = position->getBpm().orFallback(120.0);
            state.ppqPosition = position->getPpqPosition().orFallback(0.0);

            if (auto ts = position->getTimeSignature())
            {
                state.timeSigNum = ts->numerator;
                state.timeSigDen = ts->denominator;
            }

            if (state.isPlaying)
            {
                double timeInSeconds = position->getTimeInSeconds().orFallback(0.0);
                state.timecode = formatTimecode(timeInSeconds, fps);
                lastTimecode = state.timecode;
            }
            else
            {
                state.timecode = lastTimecode;
            }

            transportSenderThread.updateState(state);
        }
    }
}

juce::AudioProcessorEditor* MasterStreamerAudioProcessor::createEditor()
{
    return new MasterStreamerAudioProcessorEditor(*this);
}

void MasterStreamerAudioProcessor::getStateInformation(juce::MemoryBlock& destData)
{
    std::unique_ptr<juce::XmlElement> xml(new juce::XmlElement("MasterStreamerSettings"));
    xml->setAttribute("ip", targetIpAddress);
    xml->setAttribute("port", targetPortNumber);
    xml->setAttribute("active", isStreamingEnabled);

    xml->setAttribute("transportIp", transportIpAddress);
    xml->setAttribute("transportPort", transportPortNumber);
    xml->setAttribute("transportActive", isTransportStreamingEnabled);

    copyXmlToBinary(*xml, destData);
}

void MasterStreamerAudioProcessor::setStateInformation(const void* data, int sizeInBytes)
{
    std::unique_ptr<juce::XmlElement> xmlState(getXmlFromBinary(data, sizeInBytes));
    if (xmlState != nullptr && xmlState->hasTagName("MasterStreamerSettings"))
    {
        targetIpAddress = xmlState->getStringAttribute("ip", "255.255.255.255");
        targetPortNumber = xmlState->getIntAttribute("port", 9002);
        isStreamingEnabled = xmlState->getBoolAttribute("active", false);

        senderThread.configure(targetIpAddress, targetPortNumber);
        senderThread.setEnabled(isStreamingEnabled);

        transportIpAddress = xmlState->getStringAttribute("transportIp", "255.255.255.255");
        transportPortNumber = xmlState->getIntAttribute("transportPort", 9003);
        isTransportStreamingEnabled = xmlState->getBoolAttribute("transportActive", false);

        transportSenderThread.configure(transportIpAddress, transportPortNumber);
        transportSenderThread.setEnabled(isTransportStreamingEnabled);
    }
}

juce::AudioProcessor* JUCE_CALLTYPE createPluginFilter()
{
    return new MasterStreamerAudioProcessor();
}
