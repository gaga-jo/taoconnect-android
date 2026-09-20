# Tao Connect — Taobao en chinois + français

La version **0.5** ouvre le site mobile Taobao dans une fenêtre Android dédiée. Le chinois reste intact ; une traduction française apparaît sous les blocs de texte admissibles et défile avec eux, dans la page.

**Changement par rapport à la v0.4 :** la capture d’écran et la bulle globale sont supprimées. Cette version ne traduit pas l’application officielle Taobao ni les autres applications. Elle traduit uniquement les pages HTTPS de `taobao.com` et de ses sous-domaines dans Tao Connect.

## Utilisation

1. Installer l’APK puis ouvrir Tao Connect.
2. Toucher **Ouvrir Taobao en bilingue**.
3. Laisser télécharger les modèles Google lors de la première traduction non couverte par le glossaire.
4. Naviguer normalement. Le bouton **中 + FR** masque ou réactive le français.
5. Le menu permet d’actualiser, de réessayer un téléchargement ou d’effacer le cache de traduction.

Les liens vers d’autres domaines sont bloqués dans cette fenêtre. Un lien HTTPS touché volontairement peut être ouvert dans un navigateur externe après confirmation, sans traduction Tao Connect.

## Architecture

| Élément | Responsabilité |
|---|---|
| `MainActivity` | Accueil et ouverture du navigateur |
| `TaobaoBrowserActivity` | Navigation, réglage bilingue, pont asynchrone limité aux origines Taobao |
| `TaobaoNavigationPolicy` | Vérification HTTPS, hôte, port et absence d’identifiants dans l’URL |
| `assets/tao-bilingual.js` | Analyse des nœuds texte, annotations dans le flux, contenu dynamique |
| `BilingualTranslator` | Traduction locale Google ML Kit, file bornée, cache LRU et déduplication |
| `TranslationTextPolicy` | Glossaire Taobao, conservation des nombres et références |

AndroidX WebKit est ajouté pour un pont JavaScript asynchrone qui n’est exposé qu’aux origines autorisées. Aucune dépendance frontend, aucun compte Tao Connect et aucun backend ne sont nécessaires à cette version.

Le moteur traite progressivement les textes proches de la zone visible. Il ignore les scripts, styles, attributs, champs saisis et zones éditables. `MutationObserver` prend en compte les nouveaux contenus et les fenêtres ; `IntersectionObserver` anticipe les éléments qui approchent du champ visible. Les réponses devenues obsolètes sont rejetées. Les traductions restent en mémoire, avec au maximum 512 entrées de cache et deux opérations machine simultanées. Les annotations sont retirées proprement quand le mode est désactivé.

## Sécurité et confidentialité

- Aucune permission de capture, d’accessibilité, de superposition ou de lecture d’autres applications.
- Aucun secret ni clé API. Les textes sont traduits sur l’appareil après téléchargement des modèles Google.
- HTTPS obligatoire, erreurs de certificats bloquées, accès `file://` et `content://` désactivé, contenu mixte interdit.
- Le pont valide aussi l’origine, la page courante, la taille et la fréquence des demandes.
- Le navigateur conserve les données de session nécessaires à Taobao. Taobao reçoit les données utilisées sur son site selon ses propres règles ; « traduction locale » ne signifie pas « navigation hors ligne ».
- Les fixtures de contrôle sont uniquement dans la variante debug, dans une activité non exportée et sans option d’ouverture depuis une autre application.

## Limites explicites

Le moteur Google local produit des traductions automatiques, complétées par un glossaire relu pour les commandes courantes. Il peut se tromper sur les descriptions complexes et les noms commerciaux. Le chinois reste la référence.

Les textes dans les images, les canvas, les iframes externes et certains composants encapsulés ne sont pas traduits. Les prix, références longues et blocs contenant des URL sont volontairement laissés intacts. Le traitement est plafonné à 1 200 blocs présents pour protéger la mémoire ; les nœuds retirés de la page sont libérés.

L’ajout du français agrandit certains boutons ou titres. Les mises en page très rigides de Taobao et ses changements futurs peuvent nécessiter des adaptations. La connexion, les CAPTCHA, certains liens profonds et le paiement peuvent exiger l’application officielle. **Les tests automatiques sur fixtures ne valident pas ces parcours réels.**

## Compilation et validation

GitHub Actions exécute les tests unitaires, Android Lint, la compilation et les tests instrumentés sur Android 13. Les tests utilisent le vrai WebView, le vrai pont et une page de contrôle locale clairement identifiée. Un test distinct télécharge les modèles Google et vérifie une traduction hors glossaire. Les captures de l’émulateur et les rapports sont publiés avec le résultat du workflow ; l’APK n’est publié que si les étapes précédentes réussissent.

Java 17, Gradle 8.9 et Android SDK 35 sont nécessaires. Voir [Obtenir l’APK](docs/OBTENIR_APK.md) et [Validation](docs/TESTS_MANUELS.md).

Tao Connect est indépendant de Taobao et d’Alibaba. La v0.5 est un APK de test signé avec la clé debug du build, pas une version distribuée sur le Play Store.
