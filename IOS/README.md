# Ketvoice iPhone — natif SwiftUI, chantier en cours

Le dossier `IOS/` contient un **portage partiel**, pas la version Android entière :
recherche sur les deux services publics Ketvoice, résolution de flux HTTPS,
lecture AVPlayer avec file, commandes multimédias et arrière-plan, favoris locaux,
paroles LRC du service de publication et premiers réglages. La navigation iOS
repose sur `NavigationStack` et restaure l'écran précédent. Les surfaces de
contrôle utilisent le **vrai matériau SwiftUI** `.glassEffect` (iOS 26 et 27),
pas un flou simulé. Les effets sont gérés par le système.

**Non encore porté / non vérifié :** recherche YouTube Music, pages artistes et
albums, téléchargements, playlists Spotify/locales, Canvas et karaoké TTML,
AutoMix, authentification/édition propriétaire, onze langues, synchronisation
des favoris/réglages avec Android, tests sur iPhone. L'interface n'est donc
pas encore identique ni complètement fonctionnelle. Aucune écriture privilégiée
au serveur n'est implémentée et aucune clé secrète ne figure dans ce projet.

## Compiler sans Mac local

Le modèle de workflow [iOS source build](ci/ios-build.yml) utilise le runner
`xcode-27` de GitHub Actions (gratuit pour un dépôt public), XcodeGen et Xcode
distants. Il compile **sans signature** pour iPhone et pour simulateur, sans
lancer d'émulateur. Sa publication est bloquée tant que le jeton GitHub n'a
pas le droit `workflow` : un mainteneur autorisé doit le copier vers
`.github/workflows/ios-build.yml`. Sur Mac, `brew install xcodegen`, puis
`xcodegen generate --spec IOS/project.yml` et `xcodebuild -project
IOS/Ketvoice.xcodeproj -scheme Ketvoice -sdk iphoneos -destination
'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build`.

La clé **publique** (publishable/anon) est fournie à la compilation par
`KETVOICE_PUBLISHABLE_KEY=...` en argument de `xcodebuild`, ou dans un fichier
local non suivi, jamais une clé `service_role`. Le projet compile aussi sans
clé mais indique clairement qu'il ne peut alors pas interroger le catalogue.
Le service partagé apporte le même catalogue, pas la synchronisation des
données stockées localement sur Android.

**Un binaire non signé n'est pas un IPA installable.** Apple gère la signature
gratuite via une Personal Team dans Xcode avec profil limité à 7 jours. Un
runner public ne dispose pas de votre profil, de votre appareil ni de votre
identité Apple ; aucun IPA installable n'est publié ici. Pour l'installation
sur un iPhone personnel sans Mac, [AltServer sous Windows](https://faq.altstore.io/altstore-classic/how-to-install-altstore-windows.md)
est une piste gratuite de signature locale par Apple ID, à vérifier sur votre
appareil ; il faut toujours renouveler le provisionnement. Distribution
publique/TestFlight et accès aux certificats de distribution nécessitent une
adhésion au programme Apple Developer.

Références : [Apple — compte développeur personnel](https://developer.apple.com/help/account/basics/about-your-developer-account),
[GitHub — runners macOS publics](https://docs.github.com/en/actions/reference/runners/github-hosted-runners).
