# FlowKey spoken commands

Say these while dictating in the matching language (the language key, or the language
Whisper detects in AUTO mode). Matching ignores case, accents and punctuation Whisper
adds, so *Nový řádek.* and *novy radek* both work.

Commands marked **(end)** are also ordinary words, so they only count at the end of what
you said or right before another command: *c'est fini point* → `C'est fini.`, but
*à ce point* stays as it is.

"Scratch" commands delete everything said so far in the current dictation. They work
even when punctuation commands are switched off.

<!-- Generated from app/src/main/java/com/flowkey/ime/PunctuationCommands.kt -->

## English (`en`)

| Say | Get |
|---|---|
| *period* / *full stop* | `.` |
| *comma* | `,` |
| *question mark* | `?` |
| *exclamation mark* / *exclamation point* | `!` |
| *colon* | `:` |
| *semicolon* | `;` |
| *dash* / *hyphen* | `-` |
| *em dash* | `—` |
| *ellipsis* | `…` |
| *new line* | line break |
| *new paragraph* | blank line (new paragraph) |
| *open quote* | opening `"` |
| *close quote* | closing `"` |
| *scratch that* / *delete that* / *forget that* | scratch (delete) |

## Czech (`cs`)

| Say | Get |
|---|---|
| *tečka* | `.` |
| *čárka* | `,` |
| *otazník* | `?` |
| *vykřičník* | `!` |
| *dvojtečka* | `:` |
| *středník* | `;` |
| *pomlčka* | `-` |
| *tři tečky* | `…` |
| *nový řádek* | line break |
| *nový odstavec* | blank line (new paragraph) |
| *otevřít uvozovky* / *uvozovky začátek* | opening `"` |
| *zavřít uvozovky* / *uvozovky konec* | closing `"` |
| *smaž to* / *zruš to* | scratch (delete) |

## Slovak (`sk`)

| Say | Get |
|---|---|
| *bodka* | `.` |
| *čiarka* | `,` |
| *otáznik* | `?` |
| *výkričník* | `!` |
| *dvojbodka* | `:` |
| *bodkočiarka* | `;` |
| *pomlčka* | `-` |
| *tri bodky* | `…` |
| *nový riadok* | line break |
| *nový odsek* | blank line (new paragraph) |
| *otvoriť úvodzovky* | opening `"` |
| *zatvoriť úvodzovky* | closing `"` |
| *zmaž to* | scratch (delete) |

## French (`fr`)

| Say | Get |
|---|---|
| *point final* | `.` |
| *point* **(end)** | `.` |
| *virgule* | `,` |
| *point virgule* / *point-virgule* | `;` |
| *point d'interrogation* | `?` |
| *point d'exclamation* | `!` |
| *deux points* / *deux-points* | `:` |
| *tiret* | `-` |
| *points de suspension* | `…` |
| *à la ligne* / *nouvelle ligne* | line break |
| *nouveau paragraphe* | blank line (new paragraph) |
| *ouvrir les guillemets* / *ouvrez les guillemets* | opening `"` |
| *fermer les guillemets* / *fermez les guillemets* | closing `"` |
| *efface ça* | scratch (delete) |

## Spanish (`es`)

| Say | Get |
|---|---|
| *punto final* / *punto y seguido* | `.` |
| *punto* **(end)** | `.` |
| *punto y aparte* | `.` + line break |
| *coma* | `,` |
| *punto y coma* | `;` |
| *dos puntos* | `:` |
| *signo de interrogación* | `?` |
| *signo de exclamación* | `!` |
| *guion* | `-` |
| *puntos suspensivos* | `…` |
| *nueva línea* | line break |
| *nuevo párrafo* | blank line (new paragraph) |
| *abrir comillas* | opening `"` |
| *cerrar comillas* | closing `"` |
| *borra eso* | scratch (delete) |

## German (`de`)

| Say | Get |
|---|---|
| *punkt* **(end)** | `.` |
| *komma* | `,` |
| *fragezeichen* | `?` |
| *ausrufezeichen* | `!` |
| *doppelpunkt* | `:` |
| *semikolon* | `;` |
| *bindestrich* | `-` |
| *gedankenstrich* | `—` |
| *neue zeile* | line break |
| *neuer absatz* | blank line (new paragraph) |
| *anführungszeichen auf* / *anführungszeichen unten* | opening `"` |
| *anführungszeichen zu* / *anführungszeichen oben* | closing `"` |
| *lösch das* / *lösche das* | scratch (delete) |

## Italian (`it`)

| Say | Get |
|---|---|
| *punto* **(end)** | `.` |
| *virgola* | `,` |
| *punto interrogativo* | `?` |
| *punto esclamativo* | `!` |
| *due punti* | `:` |
| *punto e virgola* | `;` |
| *trattino* | `-` |
| *nuova riga* / *a capo* | line break |
| *nuovo paragrafo* | blank line (new paragraph) |
| *apri virgolette* | opening `"` |
| *chiudi virgolette* | closing `"` |
| *cancella tutto* | scratch (delete) |

## Portuguese (`pt`)

| Say | Get |
|---|---|
| *ponto* **(end)** | `.` |
| *ponto final* | `.` |
| *vírgula* | `,` |
| *ponto de interrogação* | `?` |
| *ponto de exclamação* | `!` |
| *dois pontos* | `:` |
| *ponto e vírgula* | `;` |
| *hífen* | `-` |
| *nova linha* | line break |
| *novo parágrafo* | blank line (new paragraph) |
| *abre aspas* / *abrir aspas* | opening `"` |
| *fecha aspas* / *fechar aspas* | closing `"` |
| *apaga isso* | scratch (delete) |

## Polish (`pl`)

| Say | Get |
|---|---|
| *kropka* | `.` |
| *przecinek* | `,` |
| *znak zapytania* | `?` |
| *wykrzyknik* | `!` |
| *dwukropek* | `:` |
| *średnik* | `;` |
| *myślnik* | `-` |
| *nowa linia* | line break |
| *nowy akapit* | blank line (new paragraph) |
| *otwórz cudzysłów* | opening `"` |
| *zamknij cudzysłów* | closing `"` |
| *usuń to* | scratch (delete) |

## Dutch (`nl`)

| Say | Get |
|---|---|
| *punt* **(end)** | `.` |
| *komma* | `,` |
| *vraagteken* | `?` |
| *uitroepteken* | `!` |
| *dubbele punt* | `:` |
| *puntkomma* | `;` |
| *streepje* | `-` |
| *nieuwe regel* | line break |
| *nieuwe alinea* | blank line (new paragraph) |
| *aanhalingstekens openen* | opening `"` |
| *aanhalingstekens sluiten* | closing `"` |
| *wis dat* | scratch (delete) |
