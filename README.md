# Controle TV — MVP TV Box (Android)

App Android (Kotlin + Jetpack Compose) que controla um **TV Box Android** por **ADB sobre a rede** (porta 5555).
Sem anúncios, 100% local. Primeira etapa do app de controle universal (ver `spec-controle-tv-unificado.md`).

## O que funciona (MVP1)
- Conectar ao TV Box pelo IP (porta 5555).
- D-Pad (setas + OK), Voltar, Home, Menu, Volume +/−, Mudo, Power, Play/Pause.
- Guarda o último IP.

## Pré-requisitos no TV Box
1. Ligar **Opções do desenvolvedor** (clicar 7x em "Número da versão" nas Configurações).
2. Ligar **Depuração USB / ADB** e, se houver, **Depuração por rede / ADB over network** (porta 5555).
3. TV Box e celular na **mesma rede Wi-Fi**.
4. Na 1ª conexão a TV mostra **"Permitir depuração USB?"** → aceitar ("sempre permitir").

## Como rodar (no celular)
- Instalar o APK (gerado pelo GitHub Actions → `controle-tv-apk`).
- Abrir o app, digitar o IP do TV Box, **Conectar**, usar o controle.

## Build
GitHub Actions (`.github/workflows/build-apk-controle-tv.yml`) compila o APK debug a cada push nesta pasta
e publica como artefato. Stack: AGP 8.5.2, Gradle 8.9, Kotlin 2.0.21, Compose, `dev.mobile:dadb`.

## Próximos passos
- Modo mouse/touchpad, teclado (input text), abrir apps (lista via `pm list`/launch).
- TV LG (NetCast/UDAP) + seletor de dispositivo (ver spec).
