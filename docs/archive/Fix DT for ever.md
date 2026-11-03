J'ai besoin de toi pour corriger un problème pourtant pas si compliqué qu'on n'a jamais résolu correctement :
- La suppression des petits arbres DynamicTrees

Quand un chemin rencontre un jeune arbre DT, que ce soit une pousse ou un arbre très jeune, le chemin passera à travers SANS le supprimer et sans peindre de blocs de chemin dans son rayon.
Cela fait que les chemins sont entrecoupés par des arbustes.

Cela ne doit pas se produire, un obstacle tel doit être si possible évité, sinon supprimé.

J'ai un mod nommé "forêt" qui entre autre choses, génère des arbustes via cette classe :
/home/user/IdeaProjects/Foret/src/main/java/com/jubitus/foret/worldgen/ForetSaplingScatterer.java

Tous les arbustes générés de cette manière, que ce soit des pousses ou des petits arbres ne sont pas pris en compte par MPB et coupent les chemins.
Les arbres un peu plus matures semblent être correctement pris en compte.