#include "PluginProcessor.h"
#include "PluginEditor.h"
#include <cmath>

// ─────────────────────────────────────────────────────────────────────────────
//  Construtor / Destrutor
// ─────────────────────────────────────────────────────────────────────────────
ClarityMeterSenderProcessor::ClarityMeterSenderProcessor()
    : AudioProcessor (BusesProperties()
                        .withInput  ("Input",  juce::AudioChannelSet::stereo(), true)
                        .withOutput ("Output", juce::AudioChannelSet::stereo(), true)),
      juce::Thread ("UDP Sender Thread")
{
    if (openSocket())
        startThread (juce::Thread::Priority::background);
}

ClarityMeterSenderProcessor::~ClarityMeterSenderProcessor()
{
    signalThreadShouldExit();
    stopThread (2000);
    closeSocket();
}


// ─────────────────────────────────────────────────────────────────────────────
//  Socket
// ─────────────────────────────────────────────────────────────────────────────
bool ClarityMeterSenderProcessor::openSocket()
{
    closeSocket();

    sock = ::socket (AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0)
    {
        DBG ("[ClarityMeter] socket() falhou: " << strerror(errno));
        return false;
    }

    // Não-bloqueante
    int flags = fcntl (sock, F_GETFL, 0);
    fcntl (sock, F_SETFL, flags | O_NONBLOCK);

    // Permite broadcast (para descoberta automática com IP 255.255.255.255)
    int broadcastEnable = 1;
    setsockopt (sock, SOL_SOCKET, SO_BROADCAST,
                &broadcastEnable, sizeof (broadcastEnable));

    // Buffer de envio generoso
    int sndbuf = 128 * 1024;
    setsockopt (sock, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof (sndbuf));

    rebuildAddr (currentIP, currentPort);

    DBG ("[ClarityMeter] Socket aberto. fd=" << sock
         << "  destino=" << currentIP << ":" << currentPort);
    return true;
}

void ClarityMeterSenderProcessor::closeSocket()
{
    if (sock >= 0)
    {
        ::close (sock);
        sock = -1;
    }
    connected.store (false);
}

void ClarityMeterSenderProcessor::rebuildAddr (const juce::String& ip, int port)
{
    sockaddr_in a {};
    a.sin_family = AF_INET;
    a.sin_port   = htons (static_cast<uint16_t> (port));

    if (inet_pton (AF_INET, ip.toRawUTF8(), &a.sin_addr) <= 0)
    {
        // IP inválido — mantém broadcast como fallback
        inet_pton (AF_INET, "255.255.255.255", &a.sin_addr);
        DBG ("[ClarityMeter] IP inválido '" << ip << "', usando broadcast.");
    }

    juce::ScopedLock sl (addrLock);
    destAddr = a;
}


// ─────────────────────────────────────────────────────────────────────────────
//  API pública (GUI thread)
// ─────────────────────────────────────────────────────────────────────────────
void ClarityMeterSenderProcessor::setTargetIP (const juce::String& ip)
{
    currentIP = ip;
    rebuildAddr (currentIP, currentPort);
}

void ClarityMeterSenderProcessor::setPort (int port)
{
    currentPort = port;
    rebuildAddr (currentIP, currentPort);
}

void ClarityMeterSenderProcessor::setEnabled (bool on)
{
    enabled.store (on);
    if (!on) connected.store (false);
}


// ─────────────────────────────────────────────────────────────────────────────
//  K-weighting (ITU-R BS.1770-4)
//  Dois filtros em série: high-shelf (stage 1) + high-pass (stage 2)
// ─────────────────────────────────────────────────────────────────────────────
void ClarityMeterSenderProcessor::computeKWeightingCoefs (double sr)
{
    // ── Stage 1: High-shelf @ 1681 Hz, +4 dB ─────────────────────────────────
    {
        const double Vh = std::pow (10.0, 4.0 / 20.0);
        const double Vb = std::pow (Vh, 0.4845);
        const double f0 = 1681.974450955533;
        const double Q  = 0.7071752369554196;
        const double K  = std::tan (juce::MathConstants<double>::pi * f0 / sr);

        const double denom = 1.0 + K / Q + K * K;
        hs_b0 = (Vh + Vb * K / Q + K * K) / denom;
        hs_b1 = 2.0 * (K * K - Vh)        / denom;
        hs_b2 = (Vh - Vb * K / Q + K * K) / denom;
        hs_a1 = 2.0 * (K * K - 1.0)       / denom;
        hs_a2 = (1.0 - K / Q + K * K)     / denom;
    }

    // ── Stage 2: High-pass @ 38.13 Hz, Q=0.5 ────────────────────────────────
    {
        const double f0 = 38.13547087602444;
        const double Q  = 0.5003270373238773;
        const double K  = std::tan (juce::MathConstants<double>::pi * f0 / sr);

        const double denom = 1.0 + K / Q + K * K;
        hp_b0 =  1.0 / denom;
        hp_b1 = -2.0 / denom;
        hp_b2 =  1.0 / denom;
        hp_a1 = 2.0 * (K * K - 1.0)   / denom;
        hp_a2 = (1.0 - K / Q + K * K) / denom;
    }

    // Zera estados
    hs_z1L = hs_z2L = hs_z1R = hs_z2R = 0.0;
    hp_z1L = hp_z2L = hp_z1R = hp_z2R = 0.0;
}

double ClarityMeterSenderProcessor::applyHsL (double x)
{
    const double y = hs_b0 * x + hs_z1L;
    hs_z1L = hs_b1 * x - hs_a1 * y + hs_z2L;
    hs_z2L = hs_b2 * x - hs_a2 * y;
    return y;
}
double ClarityMeterSenderProcessor::applyHsR (double x)
{
    const double y = hs_b0 * x + hs_z1R;
    hs_z1R = hs_b1 * x - hs_a1 * y + hs_z2R;
    hs_z2R = hs_b2 * x - hs_a2 * y;
    return y;
}
double ClarityMeterSenderProcessor::applyHpL (double x)
{
    const double y = hp_b0 * x + hp_z1L;
    hp_z1L = hp_b1 * x - hp_a1 * y + hp_z2L;
    hp_z2L = hp_b2 * x - hp_a2 * y;
    return y;
}
double ClarityMeterSenderProcessor::applyHpR (double x)
{
    const double y = hp_b0 * x + hp_z1R;
    hp_z1R = hp_b1 * x - hp_a1 * y + hp_z2R;
    hp_z2R = hp_b2 * x - hp_a2 * y;
    return y;
}

float ClarityMeterSenderProcessor::linearToDb (float linear)
{
    if (linear <= 0.0f) return kSilenceDb;
    const float db = 20.0f * std::log10 (linear);
    return juce::jmax (kSilenceDb, db);
}


// ─────────────────────────────────────────────────────────────────────────────
//  prepareToPlay
// ─────────────────────────────────────────────────────────────────────────────
void ClarityMeterSenderProcessor::prepareToPlay (double sampleRate, int /*samplesPerBlock*/)
{
    sampleRate_ = sampleRate;

    // Janela RMS = 300ms
    rmsWindowLen = static_cast<int> (sampleRate * 0.300);
    // Janela LUFS momentâneo = 400ms (BS.1770)
    lufsWindow = static_cast<int> (sampleRate * 0.400);

    rmsAccumL = rmsAccumR = 0.0;
    rmsCount  = 0;
    holdPeakL = holdPeakR = 0.0f;

    lufsAccumL = lufsAccumR = 0.0;
    lufsCount  = 0;

    computeKWeightingCoefs (sampleRate);

    sentCount.store (0);
    connected.store (false);
}

void ClarityMeterSenderProcessor::releaseResources() {}


// ─────────────────────────────────────────────────────────────────────────────
//  processBlock  ── AUDIO THREAD (real-time safe)
//  Regra: sem alocação, sem lock (exceto no flush para atômicos)
// ─────────────────────────────────────────────────────────────────────────────
void ClarityMeterSenderProcessor::processBlock (juce::AudioBuffer<float>& buffer,
                                                juce::MidiBuffer&)
{
    if (!enabled.load (std::memory_order_relaxed)) return;

    const int numSamples  = buffer.getNumSamples();
    const int numChannels = buffer.getNumChannels();
    if (numSamples <= 0 || numChannels < 2) return;

    const float* L = buffer.getReadPointer (0);
    const float* R = buffer.getReadPointer (1);

    for (int i = 0; i < numSamples; ++i)
    {
        const float l = L[i];
        const float r = R[i];

        // ── Peak instantâneo ─────────────────────────────────────────────────
        const float absL = std::abs (l);
        const float absR = std::abs (r);
        if (absL > holdPeakL) holdPeakL = absL;
        if (absR > holdPeakR) holdPeakR = absR;

        // ── RMS 300ms ────────────────────────────────────────────────────────
        rmsAccumL += static_cast<double> (l) * l;
        rmsAccumR += static_cast<double> (r) * r;
        ++rmsCount;

        // ── LUFS momentâneo (K-weighted) ─────────────────────────────────────
        const double kL = applyHpL (applyHsL (static_cast<double> (l)));
        const double kR = applyHpR (applyHsR (static_cast<double> (r)));
        lufsAccumL += kL * kL;
        lufsAccumR += kR * kR;
        ++lufsCount;
    }

    // ── Flush quando a janela estiver cheia ──────────────────────────────────
    if (rmsCount >= rmsWindowLen)
    {
        const float rmsL = static_cast<float> (std::sqrt (rmsAccumL / rmsCount));
        const float rmsR = static_cast<float> (std::sqrt (rmsAccumR / rmsCount));

        atomRmsL.store  (rmsL,      std::memory_order_relaxed);
        atomRmsR.store  (rmsR,      std::memory_order_relaxed);
        atomPeakL.store (holdPeakL, std::memory_order_relaxed);
        atomPeakR.store (holdPeakR, std::memory_order_relaxed);

        rmsAccumL = rmsAccumR = 0.0;
        rmsCount  = 0;
        holdPeakL = holdPeakR = 0.0f;
    }

    if (lufsCount >= lufsWindow)
    {
        // LUFS momentâneo: -0.691 + 10 * log10(meanSquare)
        const double msL   = lufsAccumL / lufsCount;
        const double msR   = lufsAccumR / lufsCount;
        const float  lufsL = (msL > 1e-10)
                             ? static_cast<float> (-0.691 + 10.0 * std::log10 (msL))
                             : kSilenceDb;
        const float  lufsR = (msR > 1e-10)
                             ? static_cast<float> (-0.691 + 10.0 * std::log10 (msR))
                             : kSilenceDb;

        atomLufsL.store (lufsL, std::memory_order_relaxed);
        atomLufsR.store (lufsR, std::memory_order_relaxed);

        lufsAccumL = lufsAccumR = 0.0;
        lufsCount  = 0;
    }

    // Atualiza seq para a sender thread saber que há novos dados
    atomSeq.fetch_add (1, std::memory_order_release);
}


// ─────────────────────────────────────────────────────────────────────────────
//  run()  ── SENDER THREAD (não é o audio thread)
//  Envia pacotes UDP a ~50Hz independentemente da taxa de buffer da DAW.
// ─────────────────────────────────────────────────────────────────────────────
void ClarityMeterSenderProcessor::run()
{
    uint16_t lastSeq = 0;

    while (!threadShouldExit())
    {
        juce::Thread::sleep (20); // ~50Hz de envio

        if (!enabled.load (std::memory_order_relaxed)) continue;
        if (sock < 0) continue;

        // Lê valores do audio thread
        const float peakL = linearToDb (atomPeakL.load (std::memory_order_relaxed));
        const float peakR = linearToDb (atomPeakR.load (std::memory_order_relaxed));
        const float rmsL  = linearToDb (atomRmsL .load (std::memory_order_relaxed));
        const float rmsR  = linearToDb (atomRmsR .load (std::memory_order_relaxed));
        const float lufsL = atomLufsL.load (std::memory_order_relaxed);
        const float lufsR = atomLufsR.load (std::memory_order_relaxed);
        const uint16_t seq = atomSeq.load (std::memory_order_acquire);

        // Atualiza espelhos para a GUI
        displayPeakL.store (peakL, std::memory_order_relaxed);
        displayPeakR.store (peakR, std::memory_order_relaxed);
        displayRmsL .store (rmsL,  std::memory_order_relaxed);
        displayRmsR .store (rmsR,  std::memory_order_relaxed);
        displayLufsL.store (lufsL, std::memory_order_relaxed);
        displayLufsR.store (lufsR, std::memory_order_relaxed);

        // Monta pacote
        MeterPacket pkt {};
        pkt.magic       = kMagic;
        pkt.version     = kVersion;
        pkt.numChannels = 2;
        pkt.sequence    = seq;
        pkt.peakL  = peakL;   pkt.rmsL  = rmsL;   pkt.lufsL = lufsL;
        pkt.peakR  = peakR;   pkt.rmsR  = rmsR;   pkt.lufsR = lufsR;

        // Copia addr com lock mínimo
        sockaddr_in dst;
        {
            juce::ScopedLock sl (addrLock);
            dst = destAddr;
        }

        const ssize_t sent = ::sendto (sock,
                                       reinterpret_cast<const char*>(&pkt),
                                       sizeof (pkt),        // 32 bytes — muito abaixo do MTU
                                       MSG_DONTWAIT,
                                       reinterpret_cast<sockaddr*>(&dst),
                                       sizeof (dst));

        if (sent == sizeof (MeterPacket))
        {
            connected.store (true);
            sentCount.fetch_add (1, std::memory_order_relaxed);
            lastSeq = seq;
        }
        else if (errno != EAGAIN && errno != EWOULDBLOCK)
        {
            connected.store (false);
            DBG ("[ClarityMeter] sendto erro: " << strerror(errno));
        }

        (void) lastSeq;
    }
}


// ─────────────────────────────────────────────────────────────────────────────
//  Estado (preset save/load)
// ─────────────────────────────────────────────────────────────────────────────
void ClarityMeterSenderProcessor::getStateInformation (juce::MemoryBlock& destData)
{
    juce::XmlElement xml ("ClarityMeterState");
    xml.setAttribute ("ip",      currentIP);
    xml.setAttribute ("port",    currentPort);
    xml.setAttribute ("enabled", isEnabled());
    copyXmlToBinary (xml, destData);
}

void ClarityMeterSenderProcessor::setStateInformation (const void* data, int sizeInBytes)
{
    auto xml = getXmlFromBinary (data, sizeInBytes);
    if (xml && xml->hasTagName ("ClarityMeterState"))
    {
        setTargetIP (xml->getStringAttribute ("ip",      currentIP));
        setPort     (xml->getIntAttribute    ("port",    currentPort));
        setEnabled  (xml->getBoolAttribute   ("enabled", true));
    }
}


// ─────────────────────────────────────────────────────────────────────────────
//  Editor
// ─────────────────────────────────────────────────────────────────────────────
juce::AudioProcessorEditor* ClarityMeterSenderProcessor::createEditor()
{
    return new ClarityMeterSenderEditor (*this);
}


// ─────────────────────────────────────────────────────────────────────────────
//  Entry point JUCE
// ─────────────────────────────────────────────────────────────────────────────
juce::AudioProcessor* JUCE_CALLTYPE createPluginFilter()
{
    return new ClarityMeterSenderProcessor();
}
