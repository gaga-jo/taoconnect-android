# Plan de test manuel — TaoConnect 0.3

## Installation et autorisations

- [ ] L’application démarre sans fermeture inattendue.
- [ ] Le bouton d’activation ouvre le bon écran d’autorisation superposée.
- [ ] Le refus du partage d’écran ne démarre pas le service.
- [ ] L’acceptation affiche la notification et la bulle `文 / FR`.
- [ ] Le bouton d’arrêt retire immédiatement la bulle et la notification.
- [ ] Le raccourci « Taobao FR » ouvre la demande de connexion puis Taobao.
- [ ] Une seconde pression sur le raccourci arrête le connecteur actif.

## Traduction Taobao

- [ ] Une page de résultats Taobao affiche des traductions françaises.
- [ ] Une fiche produit traduit le titre, les options et les principaux boutons.
- [ ] Le texte présent dans une image produit est détecté lorsqu’il est lisible.
- [ ] La bulle peut être déplacée sans déclencher une traduction.
- [ ] Une pression courte active le mode fluide et affiche `AUTO / FR` en vert.
- [ ] Les traductions s’actualisent automatiquement après un défilement.
- [ ] Une seconde pression arrête le mode fluide et retire les traductions.
- [ ] Les cartes de traduction ne se chevauchent pas sur une page chargée.
- [ ] Les textes secondaires sont écartés avant les titres et boutons importants.

## Robustesse

- [ ] Rotation portrait/paysage sans fermeture inattendue.
- [ ] Verrouillage de l’écran : la session de capture s’arrête proprement.
- [ ] Perte de réseau après téléchargement des modèles : traduction toujours possible.
- [ ] Écran sans texte chinois : message « Aucun texte chinois ».
- [ ] Arrêt depuis la notification : toutes les fenêtres disparaissent.

## Appareils prioritaires

Tester au minimum :

- Android 13 ;
- Android 14 ou 15 ;
- écran 720p ;
- écran 1080p avec encoche ou poinçon.

Noter pour chaque anomalie : modèle du téléphone, version Android, capture de
l’écran Taobao concerné et position de la traduction attendue.
