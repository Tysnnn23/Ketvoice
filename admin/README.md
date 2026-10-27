# Ketvoice Studio

Fichier autonome : `admin/ketvoice-admin.html`.

Page publiée : https://tysnnn23.github.io/Ketvoice/admin/ketvoice-admin.html

## Albums et durée audio

- Dans **Ajouter** ou **Modifier**, choisissez un chanteur déjà publié, puis
  l'un de ses albums. Les choix viennent uniquement des publications du compte
  propriétaire connecté, pas du catalogue général.
- Le chanteur, le nom d'album et sa pochette sont repris sur le titre. Le serveur
  relit la publication de référence, vérifie son propriétaire et sa révision :
  aucune URL de pochette fournie par le navigateur n'est copiée en base.
- Pour un nouvel album, utilisez la saisie libre et publiez son premier titre
  avec son artiste, son nom d'album et sa pochette. L'album devient ensuite
  sélectionnable. **Actualiser les albums** permet de relire les publications.
- Une pochette fournie explicitement pour un titre remplace celle héritée.
  L'héritage est une copie des métadonnées à la publication/modification, pas
  une synchronisation rétroactive de tous les titres de l'album. Il ne copie
  ni titre, ni audio, ni paroles, ni durée d'un autre morceau.
- La durée est lue localement dans les métadonnées du fichier audio, à l'ajout
  comme au remplacement. Le navigateur n'envoie pas l'audio pour cette analyse.
  Les secondes sont converties en millisecondes entières pour l'API. Un fichier
  illisible ou un délai de 15 s laisse la saisie manuelle disponible ; les
  durées absentes/nulles ne sont pas publiées par le Studio.
- L'API `/admin/albums` et toutes les écritures restent réservées côté serveur
  au compte vérifié `konnektsupport@gmail.com`. Aucune migration nécessaire :
  les albums sont regroupés par paire exacte artiste/album dans `ketvoice_tracks`.

## Vérification sans émulateur Android

```powershell
node --experimental-transform-types tools/test-content-api.mjs supabase/functions/ketvoice-content/index.ts
node --experimental-transform-types tools/test-content-edit.mjs supabase/functions/ketvoice-content/index.ts
node --experimental-transform-types tools/test-studio-albums.mjs supabase/functions/ketvoice-content/index.ts
node <browser-automation-skill>/browser.mjs about:blank --script tools/qa_admin.mjs
node <browser-automation-skill>/browser.mjs about:blank --script tools/qa_studio_albums.mjs
node tools/check-content-edit-live.mjs
node tools/check-studio-page-live.mjs
```

Les tests API utilisent des doubles réseau/Storage. Le scénario navigateur lit
de vrais fichiers WAV générés (1,25 s et 2,5 s), mais simule la connexion
propriétaire et les publications. Il couvre les albums homonymes de chanteurs
différents, la pochette héritée/personnalisée, la saisie libre, le remplacement
audio, la sélection rapide, le fichier illisible, la remise à zéro de l'éditeur
et la libération des URL temporaires. Captures locales dans
`release-assets/studio/`, non ajoutées aux sources.

Pas de vraie publication dans une session propriétaire ni de validation
physique mobile/Quest pour cette modification du Studio. Aucune nouvelle APK
n'est produite : le client Android est inchangé par cette livraison web.
Apple reste en pause et ne possède pas encore d'éditeur propriétaire natif.
