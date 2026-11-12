# b2-player

Личный музыкальный плеер для Android. Музыка лежит обычными файлами (оригиналы, без
перекодирования и шифрования) в приватном бакете Backblaze B2 `buyolitsez-music`. Плеер
стримит её напрямую из B2, своего сервера нет.

```
buyolitsez-music/
  music/<как в ~/Music>     оригиналы: FLAC, MP3, M4A (ALAC), ...
  catalog.json.gz          каталог из тегов
  covers/<album-id>.jpg    обложки 512 px
  state/likes.json         лайки (отличия от отметок каталога)
  state/playlists.json     плейлисты
  state/history.tsv        история прослушиваний: время и id трека
  state/tags.json          правки тегов с телефона, ещё не записанные в файлы
```

Старые версии файлов B2 хранит сам (lifecycle «Keep all versions»), поэтому бакет — это ещё и
бэкап музыки. `~/Favourite Music` в бакет не заливается: совпадающие с ним треки из `~/Music`
помечаются в каталоге как «любимые».

## Арч: заливка и каталог

Нужен rclone remote `b2music` с ключом Read and Write для бакета (`rclone config create b2music b2 account … key …`)
и `python-mutagen`, `python-pillow`.

```bash
tools/sync-music.sh --dry-run          # что изменится
tools/sync-music.sh                    # полная синхронизация ~/Music -> music/ (не стартует, пока идёт restic)
tools/sync-music.sh -- "rock/Swans"    # только эти подпапки
tools/publish-catalog.sh               # каталог + обложки, затем проверка, что все пути есть в бакете
tools/apply-tags.py                    # правки тегов с телефона: что изменится в ~/Music
tools/apply-tags.py --apply            # записать их в файлы и убрать записанное из state/tags.json
```

Правка тегов: «Track info» в меню трека → Edit. Телефон кладёт правки в `state/tags.json`
(`{"tracks": {"<путь>": {"GENRE": "…"}}}`, пустое значение удаляет тег) и показывает их поверх тегов
файла. `apply-tags.py --apply` пишет их в `~/Music` через mutagen и печатает команду
`sync-music.sh -- <папки> && publish-catalog.sh`. Когда в каталоге меняется размер файла, телефон
выбрасывает его старую копию из кэша стрима.

`make_catalog.py`:
- группирует треки в альбомы по папкам (`CD1`/`Disc 2` объединяются с родителем), внутри папки — по тегу album;
- чинит теги cp1251, записанные как latin1;
- разбивает рипы «один файл + CUE» на треки (`start` + `duration`, в плеере — clipping);
- обложка — файл cover/folder/front в папке, иначе первая картинка, иначе встроенная;
- пишет `unmatched-favourites.txt` — треки из `~/Favourite Music`, которых нет в `~/Music`.

## Телефон

Интерфейс на английском. Kotlin + Jetpack Compose + Media3 (ExoPlayer, `MediaSessionService` для фона и уведомления).
ALAC и прочее, что не умеет платформа, декодирует `org.jellyfin.media3:media3-ffmpeg-decoder`.

- Доступ к B2: `b2_authorize_account` (native API), потом GET `…/file/<bucket>/<key>` с токеном.
- Кэш стрима: SimpleCache 4 ГБ, LRU; ключ кэша — `b2://` URI, не зависит от токена.
- Офлайн: «Офлайн» на альбоме или «Скачать» в «Любимых» скачивает файлы целиком (WorkManager).
- Лайки хранятся в бакете в `state/likes.json` как отличия от отметок каталога (`added`/`removed`).
  Каждое нажатие сердечка сразу заливается; при запуске приложение забирает файл из бакета, если на
  телефоне нет незалитых изменений. Для этого ключу телефона нужно право записи.

- Плейлисты, импорт `.m3u` (Musicolet): треки ищутся по имени файла и папке, затем по `#EXTINF` «исполнитель - название».
- Прослушивание засчитывается после половины трека или 4 минут (треки короче 30 с не считаются); это же правило
  для истории и скробблинга в Last.fm. Ключ Last.fm API и логин вводятся в настройках, пароль не сохраняется.

- Тексты: из тегов файлов (каталог кладёт их в `lyrics/<album-id>.json`) и с lrclib.net (синхронные).
- Логи: `files/logs/app.log` в приложении, ротация по 512 КБ, всего не больше ~1,5 МБ. После ошибок
  копируются в бакет в `logs/<дата>.log` (хранится одна версия на день). Посмотреть:
  `rclone cat b2music:buyolitsez-music/logs/<дата>.log` на арче или
  `adb logcat -s 'B2P/*'` (те же строки пишутся в logcat; `run-as` работает только с debug-сборкой).

Сборка и установка (JDK 21, Android SDK):

```bash
./gradlew assembleRelease && adb install -r app/build/outputs/apk/release/app-release.apk
```

Ставить release-сборку: debug-сборка в несколько раз медленнее (каталог читается ≈1 с вместо
≈0,16 с, запуск ≈470 мс вместо ≈200 мс). Обе подписаны debug-ключом, поэтому переход между ними
сохраняет данные.

При первом запуске ввести в настройках keyID и applicationKey ключа для бакета (Read and Write, чтобы синхронизировались лайки).
