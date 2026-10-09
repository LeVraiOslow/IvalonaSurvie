# IvalonaRoyaume V2

Plugin Paper 26.2 de royaumes, conçu pour être piloté par zMenu/Nexo.

## Dépendances
- Vault + un provider d'économie
- PlaceholderAPI (recommandé pour zMenu et les gems)
- Nexo/zMenu sont optionnels : aucune dépendance dure.

## Commandes zMenu-friendly
- `/ro bank deposit <montant>` / `/ro bank withdraw <montant>`
- `/ro settings <setting> <toggle|true|false>`
- `/ro upgrade <claims|members|crop-growth|mob-spawn|bank-interest> [money|gems]`
- `/ro membres`, `/ro top`

`config.yml > menu-hooks` peut faire ouvrir automatiquement vos menus zMenu lorsque le joueur lance `/ro`, `/ro bank`, `/ro settings`, etc. Les sous-commandes restent disponibles pour les boutons zMenu.

## Settings disponibles
`pvp`, `explosions`, `doors`, `trapdoors`, `buttons`, `levers`, `containers`, `pistons`, `lava`, `water`, `fire`, `entity-interact`, `vehicles`, `mob-griefing`, `outsiders-interact`.

`true` signifie que la fonctionnalité est autorisée dans le claim. Les membres du royaume restent autorisés à construire/interagir ; les réglages concernent principalement les étrangers ou les effets environnementaux.

## Placeholders
- `%ivalonaroyaume_has_kingdom%`
- `%ivalonaroyaume_name%`
- `%ivalonaroyaume_owner%`
- `%ivalonaroyaume_rank%`
- `%ivalonaroyaume_members%`, `%ivalonaroyaume_max_members%`
- `%ivalonaroyaume_claims%`, `%ivalonaroyaume_max_claims%`
- `%ivalonaroyaume_bank%`, `%ivalonaroyaume_bank_formatted%`, `%ivalonaroyaume_bank_interest%`
- `%ivalonaroyaume_crop_growth%`, `%ivalonaroyaume_mob_spawn%`
- `%ivalonaroyaume_setting_explosions%` (même principe pour tous les settings)
- `%ivalonaroyaume_upgrade_claims%` (même principe pour tous les upgrades)

## Admin
Voir `/ro admin`. Permission : `ivalonaroyaume.admin`. Bypass claims : `ivalonaroyaume.bypass`.

## Build
Le projet Gradle cible Java 25 et Paper API 26.2.
