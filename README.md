# Detector de Ligações (Android)

App Android nativo (Kotlin) que ouve o microfone do celular — colocado embaixo/perto do headset de
atendimento — e dispara um alerta forte (som + vibração + tela cheia) quando detecta um **evento
acústico compatível com o início de uma ligação recebida no headset**. Feito para quem trabalha em
telemarketing home office à noite e precisa correr até o computador quando uma ligação chega.

**O app NÃO grava, não reconhece fala e não entende palavras.** Ele analisa características físicas
do som (nível, espectro, duração, forma de onda) quadro a quadro (~32 ms) e descarta cada quadro
imediatamente depois de analisado. Nada de áudio é salvo em disco, nem localmente nem em nenhum
servidor — o processamento é 100% local.

---

## 1. Por que não compilei o APK aqui

Este projeto foi escrito em um ambiente sem Android SDK, sem emulador, sem Gradle/kotlinc
instalados e **sem acesso à rede** (não dá para baixar o SDK, o Gradle ou dependências Maven). Por
isso:

- Não consegui rodar `./gradlew assembleDebug` nem `./gradlew test` de ponta a ponta aqui.
- **O `gradle/wrapper/gradle-wrapper.jar` (arquivo binário) não pôde ser baixado** — só o
  `gradle-wrapper.properties` (que diz qual versão do Gradle usar) foi incluído.
- A lógica do detector (a parte mais arriscada do projeto) **foi validada separadamente em Python**
  antes de ser portada para Kotlin (ver seção 6), e os testes de unidade Kotlin (seção 8) portam
  esses mesmos cenários — mas não pude executá-los aqui por falta de compilador Kotlin.

**O que fazer:** abra o projeto no Android Studio (Arctic Fox ou mais novo; recomendado Android
Studio Ladybug/Koala ou superior por causa do AGP 8.5). Ao abrir, o Android Studio detecta o
wrapper incompleto e oferece para regenerá-lo automaticamente (ou vá em **File → Sync Project with
Gradle Files**). Se preferir linha de comando e já tiver o Gradle 8.7+ instalado na sua máquina,
rode uma vez, dentro da pasta do projeto:

```bash
gradle wrapper --gradle-version 8.7
```

Isso gera o `gradle-wrapper.jar` que falta. Depois disso, `./gradlew assembleDebug` funciona
normalmente (veja seção 3).

---

## 2. Estrutura do projeto

```
DetectorLigacoes/
├── settings.gradle.kts, build.gradle.kts, gradle.properties
├── gradlew, gradlew.bat, gradle/wrapper/               (wrapper do Gradle; falta o .jar, ver §1)
└── app/
    ├── build.gradle.kts                                 (AGP 8.5.2, Kotlin 1.9.24, minSdk 31)
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/calldetector/
        │   │   ├── core/            ← DSP puro, sem dependência de Android (testável em JVM)
        │   │   │   ├── Fft.kt              FFT radix-2 iterativa
        │   │   │   ├── FrameAnalyzer.kt    extrai as características de cada quadro de 32 ms
        │   │   │   ├── DetectorConfig.kt   os 5 níveis de sensibilidade
        │   │   │   ├── CallDetector.kt     a máquina de estados do DETECTOR (candidato→confirmação)
        │   │   │   ├── Calibration.kt      calibração opcional (só números, nunca áudio)
        │   │   │   ├── FrameInfo.kt        pacote "quadro + resultado" para a tela de Diagnóstico
        │   │   │   └── Phase.kt           a máquina de estados do APP (PARADO/MONITORANDO/ALERTA)
        │   │   ├── audio/
        │   │   │   └── AudioCapture.kt     captura AudioRecord em thread própria
        │   │   ├── service/
        │   │   │   ├── MonitorService.kt   Foreground Service: dono do microfone e do alarme
        │   │   │   ├── AlertPlayer.kt      som (sintetizado) + vibração do alerta
        │   │   │   └── Notifications.kt    canais e notificações (inclui Full-Screen Intent)
        │   │   ├── app/
        │   │   │   ├── AppState.kt         estado compartilhado em memória (nunca em disco)
        │   │   │   ├── Prefs.kt            SharedPreferences (sensibilidade, calibração)
        │   │   │   └── CallDetectorApp.kt  Application: cria canais, garante início em PARADO
        │   │   └── ui/
        │   │       ├── MainActivity.kt         tela principal
        │   │       ├── AlertActivity.kt        tela de alerta em tela cheia
        │   │       └── DiagnosticActivity.kt   tela de diagnóstico/desenvolvedor
        │   └── res/                          layouts, strings (pt-BR), ícones placeholder
        └── test/java/com/calldetector/core/  testes de unidade (JVM puro, sem Android)
            ├── SignalGen.kt          gerador de sinais sintéticos (silêncio, bipe, voz, batida...)
            ├── CallDetectorTest.kt   testa o detector real contra esses sinais
            └── PhaseRulesTest.kt     testa a máquina de estados do app
```

---

## 3. Como compilar e instalar

### Opção A — Android Studio (recomendado)
1. **File → Open** e selecione a pasta `DetectorLigacoes/`.
2. Deixe o Android Studio sincronizar (ele baixa o Gradle/AGP/dependências — precisa de internet
   na primeira vez).
3. Conecte o celular via USB com **Depuração USB** ativada, ou use um emulador.
4. Clique em **Run ▶**. Isso instala o APK de debug direto no aparelho.

### Opção B — linha de comando
```bash
cd DetectorLigacoes
gradle wrapper --gradle-version 8.7   # só na primeira vez, se faltar o gradle-wrapper.jar (ver §1)
./gradlew assembleDebug               # gera app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

O build "release" está configurado para assinar com a **chave de debug** (`signingConfigs.debug`),
só para o APK instalar sem complicação. **Troque isso por um keystore seu** antes de distribuir o
app para qualquer pessoa além de você mesmo.

### Opção C — GitHub Actions (sem instalar nada no computador; ideal pra quem só tem o celular/Termux)
O repositório já inclui `.github/workflows/build-apk.yml`: um robô de build que roda nos
servidores do GitHub (não no seu celular) toda vez que você envia (`git push`) algo para o
repositório, e publica o APK pronto na aba **Releases** — de onde dá pra baixar direto pelo
navegador do celular, sem Android Studio, sem cabo USB e sem precisar de SDK instalado no Termux.

Esse workflow **não usa o `./gradlew`** (o `gradle-wrapper.jar` binário não pôde ser baixado no
ambiente sem internet onde este projeto foi gerado — ver seção 1). Em vez disso, ele instala o
Gradle e o Android SDK do zero a cada execução e chama `gradle assembleDebug` diretamente — método
testado e que evita dois problemas comuns desse tipo de configuração: (1) o wrapper ausente, e (2)
o erro do pacote `tools` do Android SDK (removido do repositório oficial), contornado instalando o
SDK com `packages: ''` e depois pedindo explicitamente só `platform-tools`, `platforms;android-34`
e `build-tools;34.0.0` via `sdkmanager`. Isso **só afeta a build na nuvem** — se você compilar pelo
Android Studio (Opções A/B acima), ele resolve o SDK e o wrapper sozinho normalmente.

### Rodar os testes de unidade
```bash
./gradlew test
```
Roda `CallDetectorTest` e `PhaseRulesTest` — pura JVM, não precisa de emulador/aparelho.

---

## 4. Como usar

1. Abra o app. Tela principal mostra **🔴 Desligado**.
2. Toque em **▶ INICIAR MONITORAMENTO**. O app pede permissão de microfone (e de notificações no
   Android 13+). Uma notificação persistente aparece — o app agora está ouvindo, mesmo com a tela
   bloqueada.
3. Coloque o celular na mesa com o headset por cima, como no seu uso real.
4. Quando uma ligação chegar e o headset tocar/falar, o app deve: **parar de ouvir imediatamente**,
   tocar um alarme alto, vibrar, e abrir a tela **📞 LIGAÇÃO DETECTADA** por cima da tela de
   bloqueio.
5. Toque em **DESLIGAR ALERTA**. Isso para o som/vibração, fecha a tela e **encerra completamente o
   monitoramento** — o microfone é liberado e o app volta a 🔴 Desligado. Ele **não volta a ouvir
   sozinho**: para monitorar de novo, toque em INICIAR MONITORAMENTO outra vez.
6. Para testar o alerta sem esperar uma ligação real, toque em **🔔 TESTAR ALERTA** a qualquer
   momento — dispara o alerta real (mesmo som, vibração e tela).

Também na tela principal: tempo de monitoramento, nível aproximado do microfone, estado do
microfone, sensibilidade atual (com botão para trocar) e um botão para excluir o app da otimização
de bateria do sistema (recomendado, para o Android não "congelar" o app durante a noite).

A **tela de Diagnóstico** (botão na tela principal) mostra em tempo real: estado interno do
detector, nível RMS, frequência dominante, razões de energia por banda, confiança do candidato
atual, e um histórico das últimas detecções/rejeições com o motivo de cada uma.

---

## 5. Máquina de estados (regra central do projeto)

```
PARADO ──[INICIAR MONITORAMENTO]──► MONITORANDO ──[evento detectado]──► ALERTA
  ▲                                                                        │
  └────────────────────────[DESLIGAR ALERTA]─────────────────────────────┘
```

- **Nunca existe transição automática de ALERTA para MONITORANDO.** Isso é garantido em código por
  `PhaseRules.allowed()` (`core/Phase.kt`), que é a ÚNICA função que decide se uma transição de fase
  é permitida — tanto `MonitorService` quanto as telas passam por ela. O teste `PhaseRulesTest`
  (seção 8) trava especificamente esse comportamento.
- Em **PARADO**, o app nunca abre o microfone (nem em segundo plano).
- **TESTAR ALERTA** é o único atalho que permite ir de PARADO direto para ALERTA — mas ainda passa
  pela mesma trava (`PhaseRules.allowed(STOPPED, ALERTING, test = true)`), então continua sendo uma
  transição verificada, não um bypass solto no código do serviço.

---

## 6. Como o detector decide ("motor" de detecção)

**Deliberadamente NÃO é**: só volume (gera falso positivo/negativo demais) nem reconhecimento de
fala/palavras (quem liga pode falar "alô" ou ficar em silêncio esperando você falar primeiro).

**É, em vez disso**, uma combinação de 6 critérios calculados a cada quadro de ~32 ms
(`FrameAnalyzer.kt`) e acumulados enquanto um "candidato" a evento está ativo
(`CallDetector.kt`):

| Critério | O que mede | Por quê |
|---|---|---|
| **Nível** | RMS acima de um piso de ruído adaptativo | Distingue som novo de ruído de fundo já presente |
| **Subida (onset)** | Quanto o nível subiu em relação a ~1s antes | Um evento novo tem início abrupto; algo que já vinha subindo aos poucos (TV que aumenta, ambiente mudando) pontua menos |
| **Duração sustentada** | Quantos quadros seguidos ficaram "ativos" | Descarta batidas/estalos/cliques em milissegundos |
| **Banda telefônica** | Fração da energia em 300–3400 Hz vs. graves/agudos | Áudio de headset/telefonia é limitado a essa banda; voz **ao vivo** perto do microfone tem muito mais graves e agudos — é o principal jeito de não confundir "o próprio usuário falando perto do celular" com "ligação chegando" |
| **Estrutura espectral** | Pico/média do espectro (tons e voz têm picos; ruído não) | Distingue tom/voz de ruído de rua, ventilador etc. |
| **Fator de crista** | Pico vs. RMS | Batidas/estalos têm pico muito acima do RMS; voz e tons não |

O piso de ruído é **assimétrico**: desce rápido (a cada quadro mais silencioso) mas sobe devagar —
assim um evento real não "vira" ruído de fundo rápido demais e continua se destacando.

Um "candidato" nasce quando nível+subida passam nos mínimos da sensibilidade escolhida; é
confirmado assim que a pontuação combinada (0 a 1) atinge o limiar da sensibilidade (latência
típica: 150–400 ms) e é descartado se o som acabar cedo, for curto demais, ou não convencer em até
1,5 s. **Assim que confirma, o detector para na hora** — o `AudioCapture` libera o microfone antes
mesmo do alarme tocar.

### Calibração opcional (arquitetura pronta, sem tela própria ainda)
`Calibration.kt` já existe e sabe: guardar até 6 "perfis" numéricos (formato espectral em 12
bandas + nível típico — nunca áudio bruto), comparar por similaridade de cosseno, e ajustar a
confiança em ±15% (nunca "zera" um evento que não bate com o perfil — evitando que a calibração
vire fonte de falso negativo). `CallDetector.setCalibration()` e `Prefs.calibration` já sabem usar
isso. **O que falta**: uma tela para o usuário gravar um perfil (ex.: "toque o app enquanto o
headset reproduz um toque de teste"). Ficou de fora da v1 porque o pedido original marcou isso como
opcional — a arquitetura já está pronta para receber essa tela depois, sem mudar o motor de
detecção.

### Degrau de ruído sustentado (experimental, desligado por padrão)
Existe um caminho alternativo (`stepLogic` em `CallDetector.kt`, ativável na tela principal) para o
cenário de **ligação totalmente silenciosa**: se o ruído de fundo sobe de forma estável por ≥4s
(chiado de linha do headset), ele dispara **mesmo sem um "evento" nítido**. Fica desligado por
padrão porque, nos testes sintéticos, ele também reage a mudanças reais de ambiente (geladeira
ligando, ar-condicionado, TV) — ou seja, tende a aumentar falso positivo. Ative e teste no seu
ambiente real antes de confiar nele.

---

## 7. Limitações conhecidas (não escondidas de propósito)

1. **Chime de notificação do próprio celular é um falso positivo conhecido e não resolvido.** Um
   bipe curto de notificação (ex.: dois tons próximos de 1,3–1,7 kHz) é acusticamente muito parecido
   com um tom curto de handshake/toque do headset — o detector confunde os dois. **Mitigação
   recomendada:** silencie as notificações do próprio celular enquanto o monitoramento estiver
   ativo (modo Não Perturbe, ou deixe o celular sem outras notificações sonoras durante o turno).
2. **Ligação 100% silenciosa, sem NENHUM ruído de linha ou de fundo, é fisicamente indistinguível de
   silêncio real usando só um microfone.** Isso não é uma limitação de implementação — é uma
   limitação física do problema. O caminho experimental da seção 6 (degrau de ruído sustentado)
   ataca o caso mais comum na prática (chiado de linha/fundo do headset), mas não cobre o caso
   hipotético de silêncio digital perfeito dos dois lados.
3. **Sensibilidade Alta/Muito Alta aumenta o risco de falso positivo** com: vozes agudas ao vivo bem
   próximas do microfone, e ruído grave sustentado de trânsito/rua. A sensibilidade **Média** é a
   recomendada como ponto de partida; suba só se estiver perdendo ligações reais, e prefira testar
   com a tela de Diagnóstico aberta para entender por que cada evento passou ou não.
4. **Batidas na mesa, TV/música tocando desde antes de iniciar o monitoramento, e ruído de rua
   moderado** são tratados corretamente na maioria dos casos testados (ver §9), mas nenhum detector
   acústico de microfone único é 100% imune a todo tipo de som do ambiente.
5. **O detector foi portado fielmente de um protótipo validado em Python/numpy** (mesmas fórmulas,
   mesmos limiares por sensibilidade), mas o comportamento final só pode ser confirmado no aparelho
   real, com o headset e o ambiente reais do usuário — daí o plano de testes da seção 9.

---

## 8. Testes automatizados incluídos

`app/src/test/java/com/calldetector/core/`:
- **`PhaseRulesTest`** — trava a máquina de estados do app (o mais importante: ALERTA nunca volta
  sozinho para MONITORANDO).
- **`CallDetectorTest`** — roda o detector real (FFT + máquina de estados de candidato) contra
  sinais sintéticos gerados em Kotlin (silêncio, bipe, voz em banda telefônica, voz "ao vivo" de
  banda larga, batida de mesa, sequência de batidas, ruído grave sustentado, chime de notificação).
  Os sinais são uma aproximação em Kotlin dos mesmos usados na validação em Python — não são
  numericamente idênticos, então os testes verificam comportamento (**detecta** / **não detecta**),
  não valores exatos de confiança.

**Não pude executar `./gradlew test` neste ambiente** (sem Kotlin compiler, ver §1) — rode você
mesmo depois de gerar o wrapper. Se algum teste falhar no seu aparelho/ambiente, isso é sinal útil
para ajustar os parâmetros em `DetectorConfig.kt`, não necessariamente um bug.

---

## 9. Plano de testes reais (no aparelho, com o headset de verdade)

Faça estes testes com a sensibilidade **Média (recomendada)** primeiro. Use a tela de Diagnóstico
para ver o motivo de cada rejeição/detecção.

| # | Cenário | Como fazer | Resultado esperado |
|---|---|---|---|
| 1 | Silêncio longo | Inicie o monitoramento e deixe o ambiente em silêncio por 10–15 min | Nenhum alerta dispara |
| 2 | Falar perto do celular | Com o monitoramento ativo, fale normalmente a ~20–30 cm do celular | Não dispara (voz "ao vivo" é banda larga; se disparar, veja limitação §7.3 e considere baixar a sensibilidade) |
| 3 | Música/TV tocando | Ligue uma música ou TV próxima e deixe o monitoramento rodando | Idealmente não dispara; se a TV for ligada DEPOIS de iniciar o monitoramento, o início pode ocasionalmente ser ambíguo (ver §7.4) |
| 4 | Ruído ambiente (rua, ventilador) | Monitore num ambiente com ruído de fundo real | Não dispara, exceto talvez em sensibilidade Alta/Muito Alta com trânsito pesado sustentado |
| 5 | Conversa próxima (não sua) | Alguém conversa perto, sem ser no headset | Comportamento semelhante ao teste 2 — não deveria disparar |
| 6 | Ligação real com fala imediata | Peça para alguém ligar; a pessoa fala assim que atende no headset | Deve disparar rapidamente (150–400 ms depois do início do som) |
| 7 | Ligação real com silêncio | Peça para alguém ligar e ficar em silêncio esperando você falar | Pode ou não disparar dependendo de haver chiado/ruído de linha perceptível (ver §7.2); ative o modo experimental de degrau de ruído (§6) e repita se este for um caso frequente no seu trabalho |
| 8 | Volumes diferentes do headset | Repita o teste 6 com o volume do headset baixo, médio e alto | Deve disparar nos três, podendo ser mais lento no volume baixo |
| 9 | Tela bloqueada | Bloqueie a tela do celular antes da ligação chegar | O alerta deve acender a tela e aparecer por cima do bloqueio |
| 10 | Com e sem carregador | Repita um teste de detecção com o celular carregando e descarregando | Não deveria haver diferença no resultado da detecção |
| 11 | Várias ativações manuais seguidas | Inicie, pare, inicie, teste o alerta, desligue, inicie de novo, várias vezes | O app sempre volta a PARADO depois de desligar o alerta ou parar, e nunca reinicia sozinho |
| 12 | Bateria durante a noite | Deixe monitorando por várias horas (turno completo) com a tela bloqueada | O monitoramento continua ativo; se parar sozinho, exclua o app da otimização de bateria (botão na tela principal) e repita |

Anote os resultados (a tela de Diagnóstico ajuda) e ajuste a sensibilidade — ou os valores em
`DetectorConfig.kt` — de acordo com o que você observar no seu ambiente real.

---

## 10. Permissões usadas e por quê

| Permissão | Por quê |
|---|---|
| `RECORD_AUDIO` | Essencial: é o microfone que tudo depende |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` | Continuar ouvindo com a tela bloqueada (exigido a partir do Android 14 para uso de microfone em serviço) |
| `POST_NOTIFICATIONS` | Notificação persistente do monitoramento (obrigatória em tempo de execução no Android 13+) |
| `VIBRATE` | Vibração do alerta |
| `WAKE_LOCK` | Manter a CPU acordada durante o monitoramento com tela bloqueada |
| `USE_FULL_SCREEN_INTENT` | Abrir a tela de alerta por cima da tela de bloqueio |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Sugerir (o usuário decide) excluir o app da otimização agressiva de bateria, para não ser "congelado" durante a noite |

Nenhuma permissão de internet é usada — o app não tem `INTERNET` no manifesto porque não precisa
enviar nada para lugar nenhum.

---

## 11. Privacidade

- Processamento 100% local, quadro a quadro; cada quadro de áudio é descartado assim que analisado.
- Nada de áudio é gravado, armazenado ou enviado — nem para servidor próprio, nem para API externa,
  nem em log.
- O único dado persistido (`SharedPreferences`, `Prefs.kt`) é: nível de sensibilidade, se o modo
  experimental está ligado, e (se você um dia gravar) o perfil numérico de calibração — **nunca**
  uma gravação de áudio.
- O histórico de eventos mostrado na tela de Diagnóstico (`AppState.kt`) vive só na memória do
  processo e some quando o app é fechado — não é gravado em disco.
