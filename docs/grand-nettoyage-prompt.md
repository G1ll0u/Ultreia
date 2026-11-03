# Grand nettoyage — prompt de lancement

**Statut** : à lancer. Cette session sert à **analyser et proposer**, pas à implémenter : aucun fichier source ne change avant que j'aie validé le plan.

## Ce que je veux

Mon Claude, je veux un grand nettoyage / refactor d'Ultreïa, qui touche la structure des packages **et** le code lui-même. Le mod a été commencé au tout début des LLMs : du code copié à la main, sans organisation, puis repris par des agents faibles. Attends-toi à des doublons, à des commentaires faux et à des classes fourre-tout. Je suis prêt à un très gros refactor si c'est justifié, mais pas à tout casser pour le plaisir : trouve les **grosses** choses qui méritent un redesign, une unification ou simplement une meilleure organisation.

Le but final, c'est que **toi** tu sois efficace :
- explorer le code doit te coûter peu de contexte (des fichiers courts, un système = un endroit) ;
- un comportement doit se modifier **à un seul endroit**, pas dans 10 classes ;
- deux logiques censées faire la même chose ne doivent jamais coexister et diverger.

Ensuite, je veux pouvoir faire évoluer le mod facilement : optimiser la création du réseau (vitesse, mémoire, qualité des routes), le rendre plus simple à maintenir en jeu (support des nouveaux villages, d'un réseau existant à compléter…), et améliorer les ponts. La centralisation rendra ces optimisations plus faciles.

Je l'ai déjà fait avec Travellers, et j'en suis très content : lis `/home/user/IdeaProjects/Travellers/docs/archive/grand-nettoyage-plan.md` pour voir le résultat attendu (phases, décisions, niveau de détail). Lis aussi le `CLAUDE.md` racine de Travellers, en particulier les sections « Code map » et « Single authorities ». C'est ce type d'organisation que je veux ici, adapté à un générateur de routes. Ne recopie pas des règles qui ne s'appliquent pas.

Je ne suis pas développeur : c'est à toi de trouver quoi améliorer et de me dire franchement si de grosses modifications sont nécessaires.

## Sollicite-moi

C'est important qu'on se comprenne bien. **N'hésite pas à me questionner, à me proposer des idées, à me contredire avec des preuves.** Pose-moi des questions dès qu'un choix de conception, un nom visible ou un compromis se présente (`AskUserQuestion`, option recommandée en premier, chaque question compréhensible seule). Si tu vois quelque chose de mieux que ce que je demande, dis-le. Si quelque chose te semble incohérent (dans le code, la doc, la mémoire ou mes demandes), signale-le, même hors sujet.

## État actuel (2026-11-03)

- Le dépôt, le `CLAUDE.md` et la mémoire viennent d'être nettoyés. Lis le `CLAUDE.md` : ses invariants (marchabilité, réalisme des ponts, sécurité async, gestion des chunks, Millénaire optionnel) restent valables, mais son « Code map » est provisoire.
- La mémoire contient les décisions sur les routes et l'A\*, sur les chunks et sur Millénaire optionnel. Lis-la avant de toucher aux coûts de l'A\* ou au réseau.
- Il n'existe ni `CLAUDE.md` de package, ni tableau d'autorités uniques, ni tests.

## Pistes déjà repérées (à vérifier, pas à prendre pour acquises)

- **Classes géantes** : `AStarPathfinder` (~2 650 lignes), `RoadPainter` (~2 200), `MPBConfig` (~1 000), `TerrainSampler` (~1 000), `MillVillageIndex` (~950), `BridgeTemplateBridgeBuilder` (~820), `GuiMPBMap` (~760), `BridgeFeasibility` (~700).
- **`gen/` est un fourre-tout** (22 classes au premier niveau : jobs, manager, A\*, peinture, terrain, chunks, zones protégées, réseau, classement des villages). `gen/newp/` a un nom qui ne veut rien dire et mélange compatibilité des arbres, compatibilité Millénaire et faisabilité des ponts.
- **Deux systèmes de ponts** : l'ancien, procédural (`gen/bridge/`), et celui à templates (`gen/bridge/template/`). Que reste-t-il de l'ancien ? Qui décide quoi ?
- **Questions de terrain** (« où est le sol », « est-ce un arbre », « peut-on peindre ici ») : probablement répondues à plusieurs endroits (`TerrainSampler`, `MPBGround`, `MPBRoadColumns`, `MPBTreeCompat`, `RoadPainter`…). C'est candidat à des autorités uniques, comme `BlockRules` / `ColumnGround` dans Travellers.
- **Deux jobs de génération** : `MPBGenerationJob` (villages) et `MPBCustomPathGenerationJob` (chemin manuel). Doublons ?
- **Config** : selon mes propres mots dans le README, le mod est « configurable, beaucoup trop ». Règle : un champ n'existe que si un joueur ou un admin voudrait vraiment le régler ; sinon c'est une constante dans le code. Trouve aussi les champs morts.
- **Préfixe `MPB`** partout, hérité de l'ancien nom. Le renommage en vaut-il la peine ? (À me demander : c'est du bruit dans les diffs, pour un gain de lisibilité.)
- **Tests** : Travellers a des tests JUnit sur la logique pure. Où en ajouter ici (géométrie des ponts, profils de pente, topologie du réseau) ?

## Comment travailler

1. **Un inventaire mesuré, pas des impressions** : tailles de fichiers, doublons réels (montre les deux copies), code mort (vérifié par recherche d'usages), dépendances entre packages, bugs trouvés en route. Tu peux utiliser des sous-agents `Explore` pour l'inventaire, afin de garder ton propre contexte léger.
2. **Discute avec moi avant de figer quoi que ce soit** (voir « Sollicite-moi »).
3. **Livrable** : un plan dans `docs/grand-nettoyage-plan.md`, au format de nos plans (statut en tête, but, décisions prises avec moi, étapes avec un statut chacune). Le plan doit être :
   - **découpé en phases indépendantes**, chacune qui compile (`./gradlew build`) et laisse le mod utilisable ;
   - **ordonné par gain / risque** : les gros gains à faible risque d'abord, les bugs trouvés en route dans une phase 0 à part ;
   - **concret** : pour chaque phase, quels fichiers bougent, ce qui fusionne, ce qui est supprimé, et ce que je pourrais remarquer en jeu (idéalement : rien) ;
   - **complet côté doc** : le `Code map` final, le tableau des autorités uniques, les `CLAUDE.md` de package prévus.

## Contraintes à ne jamais casser

- **Sauvegardes** : les mondes existants doivent toujours se charger. Le nom de sauvegarde (constante `NAME`) de `MPBWorldData`, `MPBRoadNetwork`, `MPBRoadIndexData` et `MPBJobCheckpoint` ne change pas, même si la classe est renommée, leurs clés NBT restent lues (on peut en ajouter), et les noms de catégories et de champs de config qui restent ne changent pas. Le format des templates de ponts JSON dans `config/ultreia/bridges/` doit rester compatible : des joueurs en ont créé.
- **Travellers** lit `api/MPBRoadAPI` par réflexion : aucune méthode publique renommée ni signature changée.
- **Mod id, noms de blocs et chemins d'assets** : inchangés (sinon les blocs de chemin disparaissent des mondes).
- **Mixins** : ils ciblent des classes internes de Millénaire et sont activés par un plugin conditionnel. Déplacer un mixin impose de mettre à jour le JSON mixin, et Millénaire doit rester optionnel.
- **Résultat en jeu** : un refactor ne change pas les routes générées, sauf si je l'ai décidé (pour un même monde et une même config, le même réseau).
- **Fichiers de build** : ils viennent de `devenv`, n'y touche pas (règle commune à tous les mods).

## Notes

- Ultreïa n'est jamais déployé sur le serveur de test : dis-moi où se trouve le jar.
- Je teste rarement en jeu : à la fin de chaque phase, donne-moi la courte liste de ce qui mérite vraiment une vérification.
