# Validation v0.5

## Diagnostic de la v0.4

| Décision | Motif |
|---|---|
| À conserver | Projet Android, traduction locale, glossaire, cache, contrôles des nombres et références, compilation GitHub |
| À corriger immédiatement | La capture OCR ne permet pas d’insérer une traduction sous le chinois ni de suivre exactement le défilement ; le service global peut apparaître sur d’autres applications |
| À améliorer | Qualité du glossaire, textes dynamiques, stabilité visuelle et gestion des erreurs de modèle |
| À supprimer | Service et vue de superposition, capture, permissions correspondantes, dépendance OCR et anciens messages d’activation |
| À ajouter plus tard | Adaptateurs aux mises en page Taobao réelles, traduction à la demande, exclusions configurables, éventuellement fournisseur serveur sécurisé si la qualité locale est insuffisante |

## Tests automatiques

- Tests JVM : domaines Taobao, domaines trompeurs, HTTPS, ports, URL avec identifiants, refus des prix/URL/références, fidélité des nombres et références, glossaire.
- Tests instrumentés Android : chinois préservé, prix et références inchangés, champs exclus, français situé sous le chinois, défilement synchronisé, clic sur la traduction d’un bouton, modification dynamique, fenêtre modale, contenu plus bas dans la page, absence de doublons et de requêtes répétées, désactivation et réactivation.
- Permissions du paquet : aucune capture, superposition, accessibilité ou lecture de toutes les applications.
- Vraie traduction ML Kit hors glossaire : phrase sur une chemise en coton, modèle réellement téléchargé, résultat enregistré dans les preuves.
- Captures Android : haut de page bilingue, fenêtre, après défilement et traduction machine.

Ces tests tournent sur une **page de contrôle locale**, pas sur une copie présentée comme le vrai Taobao. Leur résultat est celui du workflow associé au commit livré. Ils ne constituent pas une certification de traduction ni un audit de sécurité externe.

## Validation réelle restant nécessaire

L’accès automatisé à Taobao était bloqué dans l’environnement de développement. Il faut donc vérifier sur téléphone : accueil public, recherche, fiche produit, variantes, fenêtre de consentement, connexion officielle, panier et retour arrière, sans passer commande pour tester.

Sur chaque page : contrôler le sens, la conservation du chinois, les images/prix, le défilement rapide, les boutons, le contenu qui charge ensuite, la fermeture des fenêtres, le comportement hors connexion et le masquage du français. Les paiements et CAPTCHA ne sont pas considérés validés.

Passer ensuite dans une autre application : aucun élément Tao Connect ne doit apparaître. Les liens Tmall/1688/autres domaines restent hors du navigateur bilingue.
