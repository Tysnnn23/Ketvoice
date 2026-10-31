# Verre de contrôle — adaptation PrismalAGSL

Référence : [styropyr0/PrismalAGSL](https://github.com/styropyr0/PrismalAGSL),
révision `c853feb2e8b375fc8eb2dcce25a6d4f18250fb68`.

Les primitives de profil de lentille (`prismalArcLift`) et de reflet spéculaire
sont adaptées des shaders MIT `rect_refraction.agsl` et
`default_specular.agsl`. Le contrat de capture Backdrop et les protections de
Ketvoice sont conservés : coordonnées locales corrigées, normales nulles,
rayons asymétriques et déplacement borné à la taille du contrôle. L'avis MIT
complet est embarqué dans `assets/licenses/PrismalAGSL.txt`.

Il ne s'agit pas de l'ajout du catalogue de démonstration ou d'une dépendance
JitPack flottante. Aucun changement de SDK/Compose de production n'est requis.

## Périmètre visuel

- Capsule de navigation et lentille de sélection : fond réel de la page,
  réfraction de bord, dispersion légère et reflet directionnel.
- Bouton recherche et boutons retour : contrôle à réaction d'appui bornée ;
  animation supprimée avec les mouvements réduits.
- Réglages : accueil par catégories, pages distinctes, groupes mats. Le verre
  est réservé aux contrôles, notamment au curseur des interrupteurs. Celui-ci
  capture uniquement sa piste, pas son propre dessin.
- Les cartes et grandes feuilles de contenu utilisent le rôle `Panel`, mat,
  au lieu de transformer tous les écrans en surfaces optiques.
- La page album expose son fond décoratif dans une couche indépendante pour
  ses contrôles, sans y inclure ces contrôles eux-mêmes.

Le verre optique nécessite Android 13/API 33+ et un fond disponible. Android
12/12L, le mode performance, le flou désactivé et les contrôles sans fond
indépendant conservent un repli lisible. On ne prétend pas que ce repli est de
la réfraction AGSL.

## Vérification

- `ControlLensTest` borne les dimensions et traite les paramètres non finis.
- `SettingsNavigationTest` vérifie que les quinze groupes existants restent
  accessibles, sans doublon ni groupe perdu.
- `PrismalHostRenderTest` utilise Robolectric et Skia sur PC : navigation des
  réglages, recherche étroite avec grande police, compilation du shader et
  déplacement effectif des pixels d'une mire. Ses prévisualisations sont des
  rendus logiciels, pas des captures d'un téléphone ou d'un casque.
- `PrismalSettingsSearchTest` contient les contrôles instrumentés destinés à
  un appareil. Leur compilation ne signifie pas qu'ils ont été exécutés.

Les mesures de fluidité, l'intégration de `RenderEffect` au GPU réel, le
pointeur Quest et les sessions propriétaires nécessitent toujours les appareils
correspondants. Aucun émulateur Android/AVD n'est nécessaire aux tests hôtes.
