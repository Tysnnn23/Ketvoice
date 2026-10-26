# Ketvoice iPhone — natif SwiftUI, chantier en cours

Ce dossier contient les **sources d'un portage partiel compilé**, pas une
app iPhone validée ni la version Android entière. Parcours implémentés :

- Recherche sur les deux services publics Ketvoice et résolution de flux HTTPS.
- Lecture AVPlayer : file, aléatoire/répétition, minuterie, commandes système,
  AirPlay, gestion des interruptions et débranchement du casque.
- Favoris et playlists locales (création, renommage, ordre, suppression),
  sauvegarde atomique et protection des données locales illisibles.
- Téléchargements audio, annulation, validation de piste audio et plafond de
  300 Mo ; lecture hors ligne prioritaire.
- Paroles LRC, offsets, défilement et recherche temporelle par toucher.
- Canvas muet en boucle : arrêt à la pause/arrière-plan, désactivation selon le
  réglage et la réduction des animations utilisateur/système.

La navigation iOS repose sur `NavigationStack`. Les contrôles utilisent le
matériau SwiftUI natif `.glassEffect` (iOS 26 et 27), pas un flou simulé.

**Non encore porté :** recherche YouTube Music, pages artistes/albums, import
Spotify, karaoké mot-à-mot/TTML, AutoMix, égaliseur, capsules, transfert Wi-Fi et
authentification/édition propriétaire. Pas de synchronisation des favoris ou
réglages Android. L'interface n'est pas encore identique ni complètement
fonctionnelle. Aucune écriture privilégiée au serveur n'est implémentée.

## Ressources partagées avec Android

`python IOS/tools/sync_android_resources.py` génère les onze catalogues natifs
et la configuration **publique anon** du catalogue depuis les sources Android.
Le mode `--check` échoue en cas de désynchronisation. Le script refuse toute
clé JWT dont le rôle n'est pas `anon`. Jamais de clé `service_role` côté client.
Une clé différente peut être fournie par `KETVOICE_PUBLISHABLE_KEY=...` en
argument de `xcodebuild`. Les nouveaux publishable keys ne sont pas des JWT et
ne sont donc pas envoyés comme jeton Bearer.

Le sélecteur de langue utilise le français par défaut. Des libellés propres à
iOS restent à traduire : la présence de onze catalogues ne garantit pas encore
onze interfaces intégralement traduites. Le serveur apporte le même catalogue,
pas une synchronisation des données locales Android/iPhone.

## Validation disponible

Exécuter depuis la racine :

```powershell
python IOS/tools/sync_android_resources.py --check
python -m unittest discover -s IOS/tools -p 'test_*.py' -v
python tools/scan_release.py IOS
```

**Effectué sur Windows :** 24 tests Python des ressources et garde-fous
d'empaquetage (fixtures, pas un vrai IPA), cohérence des traductions/config,
parsing du plist et scan de sources. **Sur le runner macOS :** les 8 tests Swift
de `Tests/` passent et l'application compile pour iPhone et simulateur avec
Xcode 27.1 / SDK iOS 27.1. L'alerte du scan a été localisée aux noms de symboles
natifs contenant `AQ` : seule cette règle est neutralisée dans une **copie de
scan** de la table de symboles Mach-O ; l'exécutable d'origine, ses chaînes
littérales et les autres détecteurs ne sont pas modifiés. La validation finale
de l'artefact est en cours. **Rendu :** l'accueil a été lancé sur simulateur
iPhone 17 / iOS 27.0, puis sa capture a été téléchargée et examinée. Cela ne
valide pas les parcours, les téléchargements ou l'audio sur iPhone physique.
Voir `validation.json`.
Les deux endpoints publics `/health` répondent HTTP 200 depuis Windows avec la
configuration générée ; ce contrôle ne valide pas le client réseau iOS.

Une analyse grammaticale Tree-sitter des 10 fichiers Swift passe également,
sans remplacer le compilateur ni son contrôle des types. Pour la reproduire,
installez `IOS/tools/requirements-dev.txt` dans un environnement Python local,
puis lancez `python IOS/tools/check_syntax.py`.

## Compilation gratuite sans Mac local

Le workflow [iOS source build](../.github/workflows/ios-build.yml) utilise un runner macOS
`xcode-27` de GitHub Actions, gratuit pour le dépôt public. Il doit exécuter
`swift test --package-path IOS`, compiler pour iPhone **sans signature Apple**
et pour simulateur avec signature locale ad hoc, puis produire un IPA **non signé** avec
SHA-256 et métadonnées. L'empaqueteur refuse un binaire simulateur, une mauvaise
architecture ou un profil de signature inclus par erreur.

Le droit GitHub `workflow` est maintenant disponible et la compilation distante
fonctionne. Une étape supplémentaire lance uniquement un **simulateur iPhone**
isolé pour capturer l'accueil ; aucun émulateur Android n'est utilisé. Ce n'est
pas une validation sur appareil physique. Aucun IPA réel n'a encore passé le
scan final. Aucune identité Apple ni aucun secret de signature n'est envoyé au
runner.

Sur un Mac disposant de Xcode :

```sh
brew install xcodegen
xcodegen generate --spec IOS/project.yml
swift test --package-path IOS
xcodebuild -project IOS/Ketvoice.xcodeproj -scheme Ketvoice -configuration Release \
  -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath IOS/.build/iphone CODE_SIGNING_ALLOWED=NO build
python3 IOS/tools/package_unsigned.py \
  IOS/.build/iphone/Build/Products/Release-iphoneos/Ketvoice.app \
  IOS/.build/Ketvoice-unsigned.ipa
```

## Installation personnelle à 0 € : signature locale nécessaire

**Un IPA non signé n'est pas installable directement.** Le compte Apple gratuit
impose un provisionnement personnel à renouveler, normalement valable 7 jours.
[AltServer sous Windows](https://faq.altstore.io/altstore-classic/how-to-install-altstore-windows.md)
est une piste pour signer localement et installer avec votre propre Apple ID,
sans Mac ni abonnement développeur. Cela nécessite votre PC, votre iPhone et
votre authentification locale ; ce parcours n'a pas été testé ici. Ne mettez
jamais votre mot de passe Apple dans GitHub ou dans cette conversation.
TestFlight/distribution publique n'est pas fourni par le compte gratuit.

Références : [Apple — compte développeur personnel](https://developer.apple.com/help/account/basics/about-your-developer-account),
[GitHub — runners macOS publics](https://docs.github.com/en/actions/reference/runners/github-hosted-runners).
