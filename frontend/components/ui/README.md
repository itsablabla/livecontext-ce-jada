# Composants UI Réutilisables

Cette bibliothèque de composants contient tous les composants UI réutilisables pour le projet.

## Installation des dépendances

Pour utiliser tous les composants, installez les dépendances Radix UI suivantes :

```bash
npm install @radix-ui/react-toggle @radix-ui/react-checkbox @radix-ui/react-radio-group @radix-ui/react-slider @radix-ui/react-progress @radix-ui/react-tooltip @radix-ui/react-dialog @radix-ui/react-popover
```

## Composants disponibles

### Boutons
- **Button** - Bouton avec différentes variantes (default, secondary, outline, ghost, ghostGray, destructive, link, readonly) et tailles (sm, default, lg, icon). `default` est **le** remplissage plein : la table de variantes n'en propose pas un second (la variante `contrast`, qui codait le noir/blanc en dur, a été supprimée - `--accent-primary` est déjà quasi noir en clair et quasi blanc en sombre). Voir la section ci-dessous.

### Formulaires
- **Input** - Champ de saisie texte
- **Textarea** - Zone de texte multiligne
- **Label** - Étiquette pour les champs de formulaire
- **Select** - Menu déroulant de sélection
- **Checkbox** - Case à cocher
- **RadioGroup** / **RadioGroupItem** - Groupe de boutons radio
- **Slider** - Curseur pour sélectionner une valeur

### Contrôles
- **Switch** - Interrupteur on/off
- **Toggle** - Bouton toggle avec état pressé/non pressé

### Feedback
- **Alert** - Message d'alerte avec variantes (default, destructive)
- **Badge** - Étiquette avec variantes (default, secondary, destructive, outline)
- **Progress** - Barre de progression

### Overlays
- **Tooltip** - Infobulle contextuelle (nécessite TooltipProvider)
- **Dialog** - Modal/dialogue
- **Popover** - Popup contextuelle

### Données
- **Card** - Conteneur avec Header, Content, Footer, Description
- **Tabs** - Onglets (TabsList, TabsTrigger, TabsContent)
- **Table** - Tableau avec Header, Body, Footer, Row, Cell

## Un seul remplissage plein - source de vérité

Le bouton plein de l'application, c'est `Button` en variante `default`, et rien d'autre.
`--accent-primary` vaut déjà `#0b0d16` en thème clair et `#edecea` en sombre : un noir ou un
blanc écrit en dur donne le MÊME bouton, en dehors des tokens du thème.

| Ce qu'on veut | Ce qu'on écrit |
|---------------|----------------|
| Le bouton plein | `<Button>` (variante `default`) |
| Un remplissage accent ailleurs (pastille, compteur, case cochée) | `bg-[var(--accent-primary)] text-[var(--accent-foreground)]` |
| Son survol | `hover:bg-[var(--accent-hover)]` |

**Interdit, et vérifié par `__tests__/solidButtonFill.test.ts` :**
- une variante de `buttonVariants` qui code un remplissage quasi noir ou quasi blanc en dur
  (`bg-black`, `bg-white`, les nuances 700 à 950 et 50 à 200 de slate/gray/neutral/zinc/stone, et
  tout `bg-[#…]` dont les canaux sont extrêmes - une couleur sémantique comme le rouge
  `bg-[#dc5c5c]` de `destructive` reste légitime) ;
- un contrôle (`<Button>`, `<button>`, `<a>`, `<Link>`, ou tout élément avec un `onClick` ou un
  `role="button"/"link"`) qui peint lui-même le remplissage inversé `bg-foncé … dark:bg-clair`,
  que ce soit dans sa balise ou via une constante / fonction de classes qu'il utilise ;
- `text-white` ou `text-black` posé sur `bg-[var(--accent-primary)]` : en sombre l'accent EST
  quasi blanc, donc c'est du blanc sur blanc. La couleur de texte du remplissage est
  `--accent-foreground`, jamais un littéral.

Une ligne de filtres se construit avec des `Button` (`default` quand l'option est choisie,
`outline` sinon), pas avec une pilule maison : voir `GenerationHistoryList` et la page
Applications.

**Ce que la règle ne dit PAS.** Elle porte sur les **contrôles**, pas sur toute la page. Il
reste délibérément des surfaces non interactives en noir/blanc en dur (barres de progression,
bulles d'étape de `StepIndicator`, `SelectionActionBar`, badges « Most popular ») : ce sont des
décors et des étiquettes, pas des boutons, et le test ne les touche pas. Elle ne dit pas non plus
qu'il n'y a qu'un seul bouton plein **par écran** : une modale peut en aligner deux (p.ex.
« Compare plans » au-dessus du bouton de paiement dans `UpgradeModal`), ce qui est une décision
produit, pas une question de tokens.

## Échelle de rayons (square-rounded) - source de vérité

Tout ce que l'application affiche suit **une seule** échelle de rayons. Une capsule
(`rounded-full`) posée à côté d'un bouton carré est ce qui casse le thème, donc la
capsule est réservée aux formes qui sont réellement des cercles.

| Rayon | Pour quoi | Où c'est déjà écrit |
|-------|-----------|---------------------|
| `rounded-2xl` | une **surface flottante** qui contient des contrôles (carte de chrome canvas, panneau flottant, barre d'action flottante) | `canvasChromeSurfaceClass` |
| `rounded-xl` | un **contrôle** : Button (toutes tailles), bouton icône, carte, **étape d'une modale multi-étapes** | `buttonVariants`, `card.tsx`, `ModalStepIndicator` |
| `rounded-lg` | la piste d'un Switch (son curseur prend le cran en dessous, `rounded-md`, pour que les rayons restent concentriques) | `switch.tsx` |
| `rounded-md` | un **petit label non interactif** : badge, chip, compteur, pastille de statut textuelle | `badge.tsx`, `canvasChromeChipRadiusClass` |

**Le cran se choisit contre la HAUTEUR de la boîte, jamais à l'œil.** Un rayon qui
atteint la moitié de la hauteur redessine une capsule, quel que soit son nom : les
étapes de `ModalStepIndicator` font 32px de haut, et `rounded-2xl` (16px) en faisait
donc exactement des pilules, alors que la classe disait « carré ». Même piège sur une
bulle d'icône de 28px passée en `rounded-xl` (12px).

La règle dure est donc : **jamais la moitié de la hauteur**. Le repère de confort est
**le tiers**, et c'est exactement là que se tient le Button (12px sur 36px) : au-dessus
du tiers le coin commence à se lire comme une pilule, à la moitié c'en est une. Les
tuiles d'icône, elles, sont tenues **sous** le tiers par leur échelle dédiée. Si la
boîte a une hauteur inhabituelle, faire le calcul plutôt que de recopier un cran.

**Une tuile d'icône monte d'un cran avec sa taille**, pour que le coin garde le même
poids visuel : un rayon unique se lit comme un cercle sur une tuile de 24px et comme un
angle vif sur une de 44px. La tuile d'icône de nœud (`NodeIcon`, la seule source d'icône
pour le canvas, la palette, l'inspecteur, le panneau de run, la liste et le board de
workflows, la carte marketplace) applique donc : 24px → `rounded-md`, 32/36px →
`rounded-lg`, 44px → `rounded-xl`. Un emplacement qui dessine un substitut de cette tuile
(placeholder « cette étape n'a pas d'icône ») lit son rayon dans `nodeIconRadiusClass()`
plutôt que de le réécrire, sinon la ligne change de forme au moment où l'icône arrive.

**Une boîte qui ENTOURE une tuile d'icône est sur la même échelle, lue à SA hauteur à
elle** : `nodeIconBoxRadiusClass(hauteurEnPx)`. C'est le cas des bulles de
`WorkflowNodeIcons` (24 / 28 / 40px) sur la liste et le board de workflows, les cartes de
templates, la marketplace et les blocs de chat. Le cran choisi à l'œil est exactement ce
qui a raté : `rounded-xl` (12px) sur une bulle de 28px, c'est 12px de coin pour 14px de
demi-hauteur, donc un **cercle** posé au milieu des tuiles carrées, ce que montraient les
aperçus de templates. Ne pas réécrire un cran à la main, passer la hauteur.

**`rounded-full` reste correct** pour : avatars et images rondes, points de statut
(`w-2 h-2` et plus petits, sans texte), spinners, barres et pistes de progression,
et les décors (halos, blobs). Ces formes sont des cercles, pas des boutons.

**Une seule exception délibérée, et elle est commentée sur place** : le bouton d'envoi du
`MessageComposer` est **rond dans tous ses états** - Envoyer, Arrêter, file d'attente, et
grisé. C'est l'ancre du composer et le seul contrôle qui change d'action sous le curseur
sans bouger ; une forme qui varie avec l'état (ou avec `disabled`) le faisait lire comme
deux boutons différents, et le passage carré → rond se déclenchait au premier caractère
tapé. Ne pas « réaligner » ce bouton sur l'échelle carrée : c'est l'exception, pas un oubli.

## Exemple d'utilisation

```tsx
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card'

export default function MyComponent() {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Mon formulaire</CardTitle>
      </CardHeader>
      <CardContent>
        <Input placeholder="Entrez votre nom" />
        <Button>Envoyer</Button>
      </CardContent>
    </Card>
  )
}
```

## Page de démonstration

Tous les composants sont disponibles sur la page `/ui-components` avec des exemples de configuration et d'utilisation.

## Configuration

Tous les composants utilisent les variables CSS du projet (`--bg-primary`, `--text-primary`, `--accent-primary`, etc.) pour s'adapter automatiquement au thème (clair/sombre).

## Personnalisation

Chaque composant accepte une prop `className` pour la personnalisation supplémentaire. Les composants utilisent `class-variance-authority` (CVA) pour gérer les variantes et `tailwind-merge` via la fonction `cn()` pour fusionner les classes de manière optimale.

