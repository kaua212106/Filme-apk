# Cine Offline 3.2

Versão com **importação rápida por pasta**, mantendo o visual da versão 3.1.

## Novidades 3.2
- ⚡ **Usar pasta original**: cadastra o filme sem copiar centenas de MB para o app.
- 📥 **Copiar pasta para o app**: mantém o modo seguro antigo para quem quer apagar/mover os arquivos originais depois.
- 📦 ZIP continua disponível, agora com buffer maior e progresso aproximado em porcentagem.
- 🖼️ A capa automática é criada em segundo plano, depois que o filme já aparece na biblioteca.
- 🗑️ No modo rápido, excluir o filme da biblioteca não apaga os arquivos originais.
- ▶️ Player identifica o modo rápido e mostra uma mensagem mais clara se a pasta original tiver sido movida ou a permissão tiver sido perdida.

## Importante sobre o modo rápido
O Cine Offline guarda permissão de leitura da pasta escolhida. Para continuar reproduzindo:
- não apague a pasta original;
- não mova/renomeie a pasta ou os segmentos depois de adicionar;
- se o Android não permitir selecionar uma pasta dentro de `Android/data`, mova a pasta do filme para um local selecionável, como Downloads/Movies, ou use a importação por ZIP.

## Arquivos na raiz do GitHub
O projeto continua achatado, sem pastas internas no ZIP. O `build-apk.yml` deve continuar em `.github/workflows/build-apk.yml` no GitHub.
