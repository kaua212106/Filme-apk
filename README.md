# Cine Offline — versão com arquivos soltos

Esta edição foi reorganizada para que TODOS os arquivos deste ZIP fiquem na raiz do repositório, sem `app/`, `src/`, `res/` ou qualquer outra pasta de código.

Arquivos principais:
- `MainActivity.java` — biblioteca, importação e gerenciamento.
- `PlayerActivity.java` — player offline Media3/ExoPlayer.
- `MovieImporter.java` — lê ZIP/pasta, converte a referência `0000.ts` para o segmento `000000.dat` e cria a playlist local.
- `AndroidManifest.xml` — configuração Android.
- `build.gradle`, `settings.gradle`, `gradle.properties` — compilação.
- `build-apk.yml` — conteúdo pronto do workflow do GitHub Actions.

## Como subir no GitHub
Faça upload de todos os arquivos soltos na raiz do repositório.

## Como gerar o APK sem enviar pastas
O GitHub só reconhece workflows se o arquivo estiver em `.github/workflows/`. Você não precisa enviar essa pasta manualmente:

1. No repositório, abra **Actions**.
2. Escolha **set up a workflow yourself** / criar um workflow.
3. Apague o conteúdo sugerido.
4. Copie e cole o conteúdo de `build-apk.yml`.
5. Salve/Commit.
6. Abra **Actions > Build Cine Offline APK > Run workflow**.
7. Quando terminar, baixe o artefato **CineOffline-APK**.

O GitHub cria `.github/workflows/` automaticamente ao salvar o workflow.

## Reprodução
O app foi feito para playlists locais HLS `index.m3u8` com segmentos `.dat` ou `.ts` sem DRM. Ele não usa permissão de internet e não remove criptografia/DRM.
