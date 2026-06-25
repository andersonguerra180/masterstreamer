# Clarity Meter — Protocolo UDP

## Visão geral

O plugin VST3 envia pacotes UDP de **32 bytes** a ~50 pacotes/segundo.
O app Android os recebe e renderiza as métricas.

Sem parear manualmente: use `255.255.255.255` (broadcast) no plugin
e o Android recebe automaticamente na mesma rede Wi-Fi.

---

## Estrutura do pacote (32 bytes, little-endian)

| Offset | Tamanho | Tipo    | Campo        | Descrição                          |
|--------|---------|---------|--------------|-------------------------------------|
| 0      | 4       | uint32  | magic        | `0x4D455445` ("METE") — assinatura |
| 4      | 1       | uint8   | version      | `1`                                 |
| 5      | 1       | uint8   | numChannels  | `2` (L/R)                          |
| 6      | 2       | uint16  | sequence     | Contador incremental (0–65535)      |
| 8      | 4       | float32 | peakL        | Peak instantâneo L em dBFS          |
| 12     | 4       | float32 | rmsL         | RMS 300ms L em dBFS                |
| 16     | 4       | float32 | lufsL        | LUFS momentâneo L                  |
| 20     | 4       | float32 | peakR        | Peak instantâneo R em dBFS          |
| 24     | 4       | float32 | rmsR         | RMS 300ms R em dBFS                |
| 28     | 4       | float32 | lufsR        | LUFS momentâneo R                  |

**Total: 32 bytes** — muito abaixo do MTU de 1500 bytes, nunca fragmentado.

---

## Valores de métricas

### Peak (dBFS)
- Valor instantâneo do sample mais alto no bloco de 300ms
- Range esperado: `-120.0` (silêncio) a `0.0` (full scale)
- Acima de `-3.0` → vermelho (clipping iminente)

### RMS (dBFS)
- Média quadrática dos últimos 300ms
- Geralmente 6–20 dB abaixo do peak para material musical
- Range típico de programa: `-30` a `-6` dBFS

### LUFS momentâneo
- Calculado com K-weighting (ITU-R BS.1770-4)
- Janela de 400ms
- Alvo típico de streaming: `-14 LUFS`
- Alvo broadcast: `-23 LUFS`

---

## Configuração de rede

### Opção 1 — Broadcast (mais simples, recomendado)
- Plugin: IP = `255.255.255.255`, Porta = `50005`
- Android: faz bind em `0.0.0.0:50005` e recebe automaticamente
- Funciona sem o usuário saber o IP de nada

### Opção 2 — IP fixo (mais confiável em redes corporativas)
- Plugin: IP = endereço do Android (ex: `192.168.1.100`), Porta = `50005`
- Android: bind normal em `0.0.0.0:50005`
- O usuário digita o IP uma vez e o plugin salva no preset

---

## Implementação Android — checklist

```kotlin
// 1. Permissão no AndroidManifest.xml
<uses-permission android:name="android.permission.INTERNET" />

// 2. Criar receiver
val receiver = MeterReceiver(port = 50005)

// 3. Definir callbacks
receiver.onPacket = { pkt ->
    // pkt.peakL, pkt.rmsL, pkt.lufsL  → canal esquerdo (dBFS)
    // pkt.peakR, pkt.rmsR, pkt.lufsR  → canal direito (dBFS)
    // pkt.sequence                     → detectar pacotes perdidos
}

receiver.onConnected = { isConnected -> /* atualiza status na UI */ }

// 4. Iniciar/parar junto com o ciclo de vida
override fun onStart() { receiver.start() }
override fun onStop()  { receiver.stop()  }
```

---

## Detecção de perda de pacotes

O campo `sequence` é um contador uint16 (0–65535).
Compare com o anterior:

```kotlin
val lost = (newSeq - lastSeq - 1) and 0xFFFF
if (lost > 0) Log.w("Meter", "$lost pacotes perdidos")
```

Taxa de perda > 5% em Wi-Fi doméstico indica problema de sinal.

---

## Taxa de atualização

| Parâmetro      | Valor       |
|----------------|-------------|
| Pacotes/segundo | ~50 Hz      |
| Intervalo      | ~20ms       |
| Bytes/pacote   | 32          |
| Banda total    | ~12,8 KB/s  |

Banda extremamente baixa — não interfere com nada na rede.

---

## Compatibilidade

| Plataforma    | Suporte |
|---------------|---------|
| macOS (DAW)   | ✅      |
| Windows (DAW) | ✅ (requer WSAStartup — ver código) |
| Android 8+    | ✅      |
| iOS           | ✅ (mesma lógica, Swift) |
