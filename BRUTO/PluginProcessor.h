#pragma once

#include <JuceHeader.h>
#include <atomic>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>

// ─────────────────────────────────────────────────────────────────────────────
//  PROTOCOLO UDP — MeterPacket (32 bytes fixos)
//  Este struct é IDENTICO ao que o Android deve deserializar.
//  Byte order: little-endian (nativo x86/ARM).
// ─────────────────────────────────────────────────────────────────────────────
#pragma pack(push, 1)
struct MeterPacket
{
    uint32_t magic;       // 0x4D455445 ("METE") — assinatura do protocolo
    uint8_t  version;     // 1
    uint8_t  numChannels; // 2
    uint16_t sequence;    // contador incremental (wraps at 65535)

    float peakL;          // Peak instantâneo canal L em dBFS
    float rmsL;           // RMS 300ms canal L em dBFS
    float lufsL;          // LUFS momentâneo canal L

    float peakR;          // Peak instantâneo canal R em dBFS
    float rmsR;           // RMS 300ms canal R em dBFS
    float lufsR;          // LUFS momentâneo canal R
};
#pragma pack(pop)

// Garante tamanho em tempo de compilação
static_assert (sizeof (MeterPacket) == 32, "MeterPacket deve ter 32 bytes");

static constexpr uint32_t kMagic     = 0x4D455445u;  // "METE"
static constexpr uint8_t  kVersion   = 1;
static constexpr int      kUdpPort   = 50005;
static constexpr float    kSilenceDb = -120.0f;


// ─────────────────────────────────────────────────────────────────────────────
//  ClarityMeterSenderProcessor
// ─────────────────────────────────────────────────────────────────────────────
class ClarityMeterSenderProcessor  : public juce::AudioProcessor,
                                     private juce::Thread
{
public:
    ClarityMeterSenderProcessor();
    ~ClarityMeterSenderProcessor() override;

    // ── AudioProcessor ────────────────────────────────────────────────────────
    void prepareToPlay  (double sampleRate, int samplesPerBlock) override;
    void releaseResources() override;
    void processBlock   (juce::AudioBuffer<float>&, juce::MidiBuffer&) override;

    juce::AudioProcessorEditor* createEditor() override;
    bool   hasEditor()           const override { return true; }
    const juce::String getName() const override { return "Clarity Meter Sender"; }
    bool   acceptsMidi()         const override { return false; }
    bool   producesMidi()        const override { return false; }
    bool   isMidiEffect()        const override { return false; }
    double getTailLengthSeconds()const override { return 0.0; }
    int    getNumPrograms()      override { return 1; }
    int    getCurrentProgram()   override { return 0; }
    void   setCurrentProgram (int) override {}
    const  juce::String getProgramName (int) override { return {}; }
    void   changeProgramName (int, const juce::String&) override {}
    void   getStateInformation (juce::MemoryBlock&) override;
    void   setStateInformation (const void*, int) override;

    // ── API pública (chamada da GUI thread) ───────────────────────────────────
    void         setTargetIP (const juce::String& ip);
    void         setPort     (int port);
    void         setEnabled  (bool on);

    juce::String getTargetIP()   const { return currentIP;         }
    int          getPort()       const { return currentPort;       }
    bool         isEnabled()     const { return enabled.load();    }
    bool         isConnected()   const { return connected.load();  }
    int          getSentCount()  const { return sentCount.load();  }

    // Leituras para GUI (lock-free)
    float getDisplayPeakL() const { return displayPeakL.load (std::memory_order_relaxed); }
    float getDisplayPeakR() const { return displayPeakR.load (std::memory_order_relaxed); }
    float getDisplayRmsL()  const { return displayRmsL .load (std::memory_order_relaxed); }
    float getDisplayRmsR()  const { return displayRmsR .load (std::memory_order_relaxed); }
    float getDisplayLufsL() const { return displayLufsL.load (std::memory_order_relaxed); }
    float getDisplayLufsR() const { return displayLufsR.load (std::memory_order_relaxed); }

private:
    // ── Thread de envio UDP (juce::Thread) ───────────────────────────────────
    //    O audio thread NUNCA toca no socket.
    //    Ele apenas atualiza valores atômicos.
    //    Esta thread lê os valores e envia ~50x por segundo.
    void run() override;

    // ── Socket ───────────────────────────────────────────────────────────────
    int          sock     { -1 };
    sockaddr_in  destAddr {};
    juce::CriticalSection addrLock;

    bool openSocket();
    void closeSocket();
    void rebuildAddr (const juce::String& ip, int port);

    // ── Medição (escrita no audio thread, lida na sender thread) ─────────────
    //    Valores "prontos para enviar" — atômicos simples
    std::atomic<float>   atomPeakL { 0.0f };
    std::atomic<float>   atomPeakR { 0.0f };
    std::atomic<float>   atomRmsL  { 0.0f };
    std::atomic<float>   atomRmsR  { 0.0f };
    std::atomic<float>   atomLufsL { 0.0f };
    std::atomic<float>   atomLufsR { 0.0f };
    std::atomic<uint16_t> atomSeq  { 0 };

    // Acumuladores internos do audio thread (single-threaded — sem locks)
    double  rmsAccumL    { 0.0 };
    double  rmsAccumR    { 0.0 };
    int     rmsCount     { 0 };
    int     rmsWindowLen { 14400 }; // ~300ms a 48kHz, atualizado em prepareToPlay

    float   holdPeakL    { 0.0f };
    float   holdPeakR    { 0.0f };

    // K-weighting para LUFS (ITU-R BS.1770-4)
    // Stage 1: High-shelf pre-filter
    double hs_b0 {1}, hs_b1 {0}, hs_b2 {0};
    double hs_a1 {0}, hs_a2 {0};
    double hs_z1L{0}, hs_z2L{0}, hs_z1R{0}, hs_z2R{0};

    // Stage 2: High-pass filter
    double hp_b0 {1}, hp_b1 {0}, hp_b2 {0};
    double hp_a1 {0}, hp_a2 {0};
    double hp_z1L{0}, hp_z2L{0}, hp_z1R{0}, hp_z2R{0};

    double lufsAccumL { 0.0 }, lufsAccumR { 0.0 };
    int    lufsCount  { 0 };
    int    lufsWindow { 16000 }; // ~333ms a 48kHz (momentary LUFS)

    double sampleRate_ { 48000.0 };

    void   computeKWeightingCoefs (double sr);
    double applyHsL  (double x);
    double applyHsR  (double x);
    double applyHpL  (double x);
    double applyHpR  (double x);

    static float linearToDb (float linear);

    // ── Espelhos para a GUI (atômicos) ───────────────────────────────────────
    std::atomic<float>  displayPeakL { kSilenceDb };
    std::atomic<float>  displayPeakR { kSilenceDb };
    std::atomic<float>  displayRmsL  { kSilenceDb };
    std::atomic<float>  displayRmsR  { kSilenceDb };
    std::atomic<float>  displayLufsL { kSilenceDb };
    std::atomic<float>  displayLufsR { kSilenceDb };

    // ── Config ────────────────────────────────────────────────────────────────
    juce::String      currentIP   { "255.255.255.255" }; // broadcast = descoberta automática
    int               currentPort { kUdpPort };
    std::atomic<bool> enabled     { true  };
    std::atomic<bool> connected   { false };
    std::atomic<int>  sentCount   { 0     };

    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR (ClarityMeterSenderProcessor)
};
