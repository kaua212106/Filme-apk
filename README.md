# Cine Offline 2.0

Versão visual refeita para combinar com o estilo dos outros apps: gradiente roxo/azul, cartões arredondados, menu lateral, busca, barra inferior e capa automática.

## Arquivos na raiz
- AndroidManifest.xml
- MainActivity.java
- PlayerActivity.java
- Movie.java
- MovieRepository.java
- MovieImporter.java
- ImportResult.java
- Ui.java
- build.gradle
- settings.gradle
- gradle.properties
- icone.png

O workflow `build-apk.yml` continua igual e deve ficar em `.github/workflows/build-apk.yml`.

## Recursos
- Importação por ZIP ou pasta
- index.m3u8 + .dat/.ts
- Reprodução 100% local depois da importação
- Capa automática extraída do próprio vídeo
- Capa personalizada
- Busca
- Favoritos
- Histórico
- Continuar de onde parou
- Progresso visual
- Velocidades de 0,5x a 2x
- Voltar/avançar 10 segundos
- Reiniciar vídeo
- Tela cheia
- Rotação
- Renomear e excluir filmes


### Correções 3.1
- Janela de importação refeita com botões visíveis em qualquer tema Android.
- Contadores da tela inicial reduzidos e unidos em uma barra compacta.
