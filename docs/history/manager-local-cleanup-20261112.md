# Local Manager cleanup archive - 2026-11-12

Before local cleanup, the remaining research, UI design mockups, legacy source patches and original branch heads are preserved on `side/mike-manager`.
The earlier eight draft snapshots remain reachable through the first parent.
Application source is unchanged; research PNGs are generated product mockups, not personal captures.

Local cleanup targets are the worktrees and branches below, the Manager temporary build directories and three Manager APK copies. Remote branches and the installed phone app are outside this cleanup.

## Original local branches

| Branch | Saved head |
| --- | --- |
| `backup/proactive-manager-1200-pre-cleanup` | `731a9ba563dd25bd0b186306e6d2608340ae8211` |
| `backup/proactive-manager-1201-precommit-rewrite` | `480d411ebccbcee546ba83eaa6d0e670511b544b` |
| `feat/location-place-20261112` | `dbf9539e6a2de436abb96b3b51a1a707cb50e897` |
| `feat/manager-20261112` | `a7bf6666091cb0287511f839d0cb52418b688ae2` |
| `feat/manager-eye-on-1177` | `6bf1f35b8219c8fa697831a11aa207945bb1ecb3` |
| `feat/mike-orb-eyes` | `8d0f088da2ae214eca0a8c6f332662aebe7a9cb6` |
| `feat/personal-assistant-flow` | `c65fe575ab56710e8948de3126a8cee967e01c9a` |
| `feat/proactive-task-board` | `053cc14ab94582efba6f1015307fde2bcdb05169` |
| `feat/task-board-reliability` | `6bf1f35b8219c8fa697831a11aa207945bb1ecb3` |
| `fix/personal-profile-notifications` | `1d67fe6d8daebb753a7582711d27b5847acbdf2d` |
| `integrate/manager-state-widgets-20261104` | `fc286d88e6ad0618a5dab1d06851dacb7ffb0ed3` |
| `revert/mike-manager-from-dev` | `c73cc8ff2f19d101ea260254f7a2a20bfde9bf03` |
| `feat/assistant-flow-limits-20261028` | `c65fe575ab56710e8948de3126a8cee967e01c9a` |
| `fix/computer-use-approvals-20261028` | `b9ea1510d594ca18e174eb2a58344293c6d4eea9` |
| `feat/whatsapp-widget-probe-20261027` | `6bf1f35b8219c8fa697831a11aa207945bb1ecb3` |
| `feat/context-meter-on-1174` | `6bf1f35b8219c8fa697831a11aa207945bb1ecb3` |
| `claude/session-20261102` | `ddc477925e8e6f71c8cfb37aba435d9c2f5b5fda` |

## Worktrees

- `C:\Users\SHIRA\.codex\worktrees\assistant-flow-limits-20261028`
- `C:\Users\SHIRA\.codex\worktrees\computer-use-approvals-20261028`
- `C:\Users\SHIRA\.codex\worktrees\manager-eye-on-1177-20261027`
- `C:\Users\SHIRA\.codex\worktrees\personal-assistant-flow-20261028`
- `C:\Users\SHIRA\.codex\worktrees\task-board-v2-20261026`
- `C:\Users\SHIRA\.codex\worktrees\whatsapp-widget-probe-20261027`
- `C:\Users\SHIRA\.codex\worktrees\install-conversation-inbox-20261031`
- `C:\Users\SHIRA\.codex\worktrees\personal-profile-notifications-20261026`
- `C:\Users\SHIRA\Documents\AI\Android-agent-use\.claude\worktrees\context-meter-1175-20261027`
- `C:\Users\SHIRA\Documents\AI\Android-agent-use\.claude\worktrees\proactive-tasks-20261025`
- `C:\Users\SHIRA\Documents\AI\Android-agent-use\.claude\worktrees\mike-orb-20261027`
- `C:\Users\SHIRA\Documents\AI\Android-agent-use\.claude\worktrees\missing-info-20261112`
- `C:\Users\SHIRA\Documents\AI\Android-agent-use\.claude\worktrees\session-20261102`

## Archived file manifest

```json
{
  "docs/research/proactive-mike-2026-10-25.md": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-mike-2026-10-25.md",
    "sha256": "d1e8400ac47d791e7ab8310a1453ba19b2249f7c18badf49f2ec9119333ddec0",
    "size": 42742,
    "blob": "b4412eff6f592951858b84e566679d27528f0982"
  },
  "docs/research/proactive-v2/00-synthesis.md": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\00-synthesis.md",
    "sha256": "96efc49891c0aafabc0cdaddf0bf6c0265ef5e7bad3ea66349f95dd764463c63",
    "size": 14482,
    "blob": "7d893ab18d52bd011fb27bdb45a1bc3283e1a971"
  },
  "docs/research/proactive-v2/01-ecosystem-and-code.md": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\01-ecosystem-and-code.md",
    "sha256": "dafbe5ed0d2ef6c952bb19eb263f8beb28debb11332dfe80c187c81f660fa14c",
    "size": 73849,
    "blob": "d1ac63670cfbe61392b974f104e40329abbc4399"
  },
  "docs/research/proactive-v2/02-science-and-evaluation.md": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\02-science-and-evaluation.md",
    "sha256": "027609b789dd8611cc03144d3165b1539f51a248c13057f7711570c927506e79",
    "size": 83819,
    "blob": "7ff88f249bec742c7bfbd1c731b9a9abdb0bb55c"
  },
  "docs/research/proactive-v2/03-mike-architecture.md": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\03-mike-architecture.md",
    "sha256": "530483555baad4c3cf9123e4ff8250dc8128d85d53ffc1a1256c731459d7aa1f",
    "size": 63958,
    "blob": "f82feaf0f17ef398b6efc484f3f3413a4289158f"
  },
  "docs/research/proactive-v2/04-task-inbox.md": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\04-task-inbox.md",
    "sha256": "a9e9b0e4ecd93ccf5e78ac612fd0d55b6fdd0d86addd1540e06f90d17ead7083",
    "size": 39964,
    "blob": "55f4fc11aca70ac1b7619a408f0fb30d4e4f5c41"
  },
  "docs/research/proactive-v2/design/_src/build.py": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\build.py",
    "sha256": "810f96febc9678a993b3c62ad5e730e8bc313ebc381025ac7afeeac7f256e83f",
    "size": 1728,
    "blob": "8a931933a4383337ea8503f1ce1e4f579796603b"
  },
  "docs/research/proactive-v2/design/_src/jobs.py": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\jobs.py",
    "sha256": "42482181e5e32b8d47a31928c140d09d5adf917f3ad11f36f6ca051f12b83d0b",
    "size": 2041,
    "blob": "8456a6589a4b12d4a265ca0ed6a1667bb9842036"
  },
  "docs/research/proactive-v2/design/_src/members.py": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\members.py",
    "sha256": "b6eab88eedce06583f26772c294b0ec3be2f25edbb221ec905950459343fe2e0",
    "size": 7501,
    "blob": "d84187276bc82a80e5f768280282e42fe07164c2"
  },
  "docs/research/proactive-v2/design/_src/pages/avatars.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\pages\\avatars.html",
    "sha256": "6d26f0682ebfaa3fc7f82cb1ecedbd2fa9343b019396a9d4f2387b199c564fe7",
    "size": 7532,
    "blob": "3b44f977ae596fcd5c757c28ac6b5ee8db17c4dd"
  },
  "docs/research/proactive-v2/design/_src/pages/board.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\pages\\board.html",
    "sha256": "13ef0bf296f7ba2318e5708716fd499d5390d87a1477b5bedab5918d8eec0d41",
    "size": 10831,
    "blob": "f0af8decb7c5f173ef8d2fc83dbc3609ada473a4"
  },
  "docs/research/proactive-v2/design/_src/pages/card-detail.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\pages\\card-detail.html",
    "sha256": "fa5d6778c835309041304866f40bd8b8c47c584b612a6d3f2dee359d5cb16c29",
    "size": 8815,
    "blob": "984a1e6a8bc6b7f5e48a97d532141600124626ab"
  },
  "docs/research/proactive-v2/design/_src/pages/cards.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\pages\\cards.html",
    "sha256": "fb90eadce77da392fc7b0777495478c84f433c4517b940bac62f928700a4b333",
    "size": 15348,
    "blob": "1b80a72d797c3388c47768a6fe989435a91674ef"
  },
  "docs/research/proactive-v2/design/_src/pages/manager-chat.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\pages\\manager-chat.html",
    "sha256": "e118d0858dcdc6e41dedea5123ff313401372e03d753867e4b6dcad071a37020",
    "size": 6211,
    "blob": "0c5be1751540a574693eb3f1a4b14fc4eaa3c6fa"
  },
  "docs/research/proactive-v2/design/_src/pages/member.tpl.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\pages\\member.tpl.html",
    "sha256": "d301bc366266bda6b51435130a0bf11ecb15bdf3a709bd6cd8e3f46b6761dcb8",
    "size": 2476,
    "blob": "b83f53f9e24e39bcbe70c961e1ce79e159a9f9a0"
  },
  "docs/research/proactive-v2/design/_src/pages/memory.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\pages\\memory.html",
    "sha256": "ef5979708e8c821ea242b05c646504910036c29418014d757e455d9522b7566f",
    "size": 6479,
    "blob": "ad609827738a28aa03f81cfc2bcf53a8a10d6073"
  },
  "docs/research/proactive-v2/design/_src/pages/orb-faces.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\pages\\orb-faces.html",
    "sha256": "149f3cb78586a325633b9974c96971f68371263040e3675df4c2942831c04ac9",
    "size": 9219,
    "blob": "b67070f891f604ad469c36bf45b88e3584243675"
  },
  "docs/research/proactive-v2/design/_src/pages/sources.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\pages\\sources.html",
    "sha256": "4886bd38d196caa90e9797a86c90c9a63db281f50c2f40d3627d0a13aa7236e5",
    "size": 6343,
    "blob": "4e387c7b9ef7d54fd622a75fb4c345f2cb2424db"
  },
  "docs/research/proactive-v2/design/_src/pages/strip-states.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\pages\\strip-states.html",
    "sha256": "8f3688f195fb8c905b3755b33a54c808e4114279675a609dec5908d81d7df9dc",
    "size": 5510,
    "blob": "fcd1f8b8028a71242dbc5d2b9e28e8ed7435bb9a"
  },
  "docs/research/proactive-v2/design/_src/pages/strip.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\pages\\strip.html",
    "sha256": "9643dd9bbe86bf5afaeaff2ad282f3d16288b115680f2c413ce0386052435c76",
    "size": 4162,
    "blob": "ab4191814ead52330bd19b228d3ceb37b38c37ae"
  },
  "docs/research/proactive-v2/design/_src/pages/team.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\pages\\team.html",
    "sha256": "13aa5ceffb8360ba1cfe782da13643ffbfc67dddd9e6255c49589a9461b02e02",
    "size": 5099,
    "blob": "e5207438e7791ebedd06cb39dede5484d3b60b31"
  },
  "docs/research/proactive-v2/design/_src/parts/components.css": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\parts\\components.css",
    "sha256": "4038376ec7afa79aef0b8c5d5b09bba0b4445f52f6acb5b7c99a13de675f5e5c",
    "size": 11484,
    "blob": "494b8eed8b1cd315f5968ce166631a8e800be81d"
  },
  "docs/research/proactive-v2/design/_src/parts/icons.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\parts\\icons.html",
    "sha256": "c0bc13f9b6032b0b2c830d9f3bf60b48055f00346ad04004071ad324851e829a",
    "size": 5683,
    "blob": "1b810973770635371b4d082462bd520fef3b70a3"
  },
  "docs/research/proactive-v2/design/_src/parts/manager.css": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\parts\\manager.css",
    "sha256": "e23a683ad11689fb107716f7999dc30a5026ba92253d8e532793a1549b010812",
    "size": 4850,
    "blob": "34a8666d50bbdb0bef327ecba3166a046d143b5e"
  },
  "docs/research/proactive-v2/design/_src/parts/orb.js": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\parts\\orb.js",
    "sha256": "761139c801f1791a235caae106829e20e86df0efde17723231a8fecb7343c5e0",
    "size": 27905,
    "blob": "c9cde6c3a67ccd51a987c994f18ab8ae63817687"
  },
  "docs/research/proactive-v2/design/_src/parts/page.js": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\parts\\page.js",
    "sha256": "c94d75cb36d5bc3b0beb6ef909b512d3f3ad2e93d6e9e526e318d77bc370172a",
    "size": 1114,
    "blob": "f05625f67d0e4727cab702943539049c420ee51d"
  },
  "docs/research/proactive-v2/design/_src/parts/strip.css": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\parts\\strip.css",
    "sha256": "7d02e3585405e9790fc1d032d5a9442228a4814d886f4cef846bc0c72e18d38c",
    "size": 3818,
    "blob": "5f6fb58423484427786fa28c6f77ce3d0b455de4"
  },
  "docs/research/proactive-v2/design/_src/parts/tasks.css": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\parts\\tasks.css",
    "sha256": "f9740d04311541eeae30d0748c3202735bb34c94c08a1d856ad2c7c272f94871",
    "size": 4846,
    "blob": "78bb8c8878e4e5fbe54b457588619c9887623d10"
  },
  "docs/research/proactive-v2/design/_src/parts/team.css": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\parts\\team.css",
    "sha256": "be405d701530524d6364d42a2959fef9556474a73c110c4b4b9ae9ecda91111f",
    "size": 6886,
    "blob": "8fada475bee380bd03da8431c98f11e9205544b6"
  },
  "docs/research/proactive-v2/design/_src/parts/tokens.css": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\parts\\tokens.css",
    "sha256": "67d6cc864ecaa7a7008616fdefd3ff78321e07e84c84bc8f6c86a98109a74121",
    "size": 3632,
    "blob": "d36700879e3a23dbf12abec6ae4d11f98d0372a9"
  },
  "docs/research/proactive-v2/design/_src/render.py": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\_src\\render.py",
    "sha256": "0b977def26291352b17e1554d6e99807259f827704723ff7818ad678cd1147b2",
    "size": 2453,
    "blob": "f2c0c7ede9ae19acc40102df7b0ceb359ac5a7a6"
  },
  "docs/research/proactive-v2/design/avatars-light.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\avatars-light.png",
    "sha256": "687eb31ee0319e76afcb243526d63104b62dbb1370b4c3df068f2791148a9963",
    "size": 1578471,
    "blob": "87db1c76e42a18e653e50fcbcbac21a408366f70"
  },
  "docs/research/proactive-v2/design/avatars.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\avatars.html",
    "sha256": "703d21e671e780f31493601055f05a0ceb89d82b29527ecbdd3d83a92ed31567",
    "size": 57272,
    "blob": "dd7b213801f7d2cc2a9fd2480ace6dcea45bd30d"
  },
  "docs/research/proactive-v2/design/avatars.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\avatars.png",
    "sha256": "5f2844a7cbaddf21c0ec516af0bcb462155c0267b1218c99990d7c9242ace7f8",
    "size": 1567923,
    "blob": "e26e50a0acce7e55874b296f40c18ee9f5d08f56"
  },
  "docs/research/proactive-v2/design/board-full.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\board-full.png",
    "sha256": "417cae1ca9d66f311237278230d87a5b59d7030c3597ca1ebd2734b9ea7d940f",
    "size": 378430,
    "blob": "13044ac11efc4407f3973dfe5cca9e92a128cd54"
  },
  "docs/research/proactive-v2/design/board-light.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\board-light.png",
    "sha256": "9f9316e0ca17bbaf1ac1df53258ff3fd1d102715409455fd088389a1ac7daef9",
    "size": 182553,
    "blob": "1f1a76dcf7aed9833e3527470a3af1f6da54d486"
  },
  "docs/research/proactive-v2/design/board.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\board.html",
    "sha256": "8e93c73cd632466a851129eadb5b39df2851a4840f626aadaec0fdd737c30784",
    "size": 65441,
    "blob": "57dd0a474d0f8cc0daa52071d6f2bc45ff9d0c0f"
  },
  "docs/research/proactive-v2/design/board.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\board.png",
    "sha256": "ead4657275ad2cd66a5482942ff693d62b8aadaaf762019c884a8a6771cb9428",
    "size": 177660,
    "blob": "0c943a45931b429489124089182eebf1c206c65f"
  },
  "docs/research/proactive-v2/design/card-detail-full.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\card-detail-full.png",
    "sha256": "1c19238681291e4f4cde14916dd251ea25845bdc9e16995f2fbfb2a34ab56c27",
    "size": 154770,
    "blob": "5117fd7c4d95b6ab64fffcd11a4ac93997b54113"
  },
  "docs/research/proactive-v2/design/card-detail-light.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\card-detail-light.png",
    "sha256": "e6b7c450d615d7d7d65923dc7f0117b5f7d41d689ff70b4d58e0db99525a21ab",
    "size": 115039,
    "blob": "2faa58a989bc4afcb43796b6eddf57ffaafd9dbf"
  },
  "docs/research/proactive-v2/design/card-detail.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\card-detail.html",
    "sha256": "eb2755eff9feefbeda837dc55c2c5b4055a6338e4bfe52b9a53547666fa743f5",
    "size": 63425,
    "blob": "080cc197ba1782a74f27e76aa886eb949f078a13"
  },
  "docs/research/proactive-v2/design/card-detail.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\card-detail.png",
    "sha256": "4359cd1213a65dd6f6ca679ff83fd07ce0eac97a5cb42e6496abe5809d53fdd9",
    "size": 101220,
    "blob": "50f7acd584773f44e7cd2671c09b501160edffd7"
  },
  "docs/research/proactive-v2/design/cards-light.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\cards-light.png",
    "sha256": "03835d51a61e9abd70bce8b3bf7989604d82a7b561510b9c0f383597fdbb8870",
    "size": 532317,
    "blob": "4388739d11ba772218369a4006ccdc4478acab9b"
  },
  "docs/research/proactive-v2/design/cards.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\cards.html",
    "sha256": "f4c61119b748287745dad7689811f9ddc71f1a776935e6ea26e25f8a9d05a254",
    "size": 69958,
    "blob": "364de92f61720852791c836edca888bee4728f3a"
  },
  "docs/research/proactive-v2/design/cards.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\cards.png",
    "sha256": "8a9a6fee5e6ef8109365b131ecbbddf77afe39dda597b5143bf74a4e004e73d0",
    "size": 484328,
    "blob": "cc8ef17edfe19b88cff2cc7e21d7d57b54a58b7d"
  },
  "docs/research/proactive-v2/design/design.md": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\design.md",
    "sha256": "0b4ca2c891401e74865079d9be5e89c5fdc29f4a3919d289d6136a43c7e1a7db",
    "size": 47788,
    "blob": "13b5c931e453edb519e7196b7ddbf49a281dd7a7"
  },
  "docs/research/proactive-v2/design/manager-chat-done.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\manager-chat-done.png",
    "sha256": "53e31b20e3254f744170f2cb8e13ccec0c49454656f8ed03e3d5a8d26d121f70",
    "size": 424017,
    "blob": "5428a4c3b29423ba938c7e105fb72861ffd83aae"
  },
  "docs/research/proactive-v2/design/manager-chat-light.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\manager-chat-light.png",
    "sha256": "249010ea7a874d8a68e202a90958a90ee4a41f761de5c8d83e6c02a66e960d77",
    "size": 398214,
    "blob": "c18de6e32f2ddbff6aa1d380b78bdbfc4ad76a16"
  },
  "docs/research/proactive-v2/design/manager-chat-thinking.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\manager-chat-thinking.png",
    "sha256": "c1f01f9aa0aed6a1dfb435b187a12bc43551803600017891cd6b8b96c2fe44e4",
    "size": 421565,
    "blob": "ff56ed51d35bfac202bbe429d6010eda43087cad"
  },
  "docs/research/proactive-v2/design/manager-chat-working.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\manager-chat-working.png",
    "sha256": "97fa124103839b871c949c688bf178d6a0841d4d8a6dd4a6b1cfb49b0100d023",
    "size": 421348,
    "blob": "82dd201be7f9160af8d7142b8cc614145c7d4232"
  },
  "docs/research/proactive-v2/design/manager-chat.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\manager-chat.html",
    "sha256": "d0a11ce4046d2d17935599cc0eeb7264c6cb33501bee672bda058c22165fa4c9",
    "size": 65641,
    "blob": "95b2a8546737d760056a4f0f560b487ed9e9a6fe"
  },
  "docs/research/proactive-v2/design/manager-chat.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\manager-chat.png",
    "sha256": "fee1f3bcaf37bd409a2186b3143c5951e984e93b0f8e4c78ed23b6ecad625b30",
    "size": 415461,
    "blob": "47088ce25ef9f87bdf832b346b7a7e647c2feb9c"
  },
  "docs/research/proactive-v2/design/member-coach.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\member-coach.html",
    "sha256": "6865e0142c11c6fb6798f28ea943660689ac82f2b4ee0c56df8979bd1c85ec8f",
    "size": 71260,
    "blob": "e22e8390b1e38571fc087571e047e5c7a4a179f5"
  },
  "docs/research/proactive-v2/design/member-coach.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\member-coach.png",
    "sha256": "68db13a41877c22e250fa7bd175c26cdba52a78066c2e99cc7e604a15d38a8df",
    "size": 379257,
    "blob": "c4e7ece18a9ee306c3112f3d84f30916a0993af0"
  },
  "docs/research/proactive-v2/design/member-full.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\member-full.png",
    "sha256": "ec1802cd34cd07b1e894114c1bb36789c9f49a562f1af97a107d74be4c3762fd",
    "size": 424513,
    "blob": "7c425dfda43611d21ef4d2145abc4e909e705504"
  },
  "docs/research/proactive-v2/design/member-librarian.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\member-librarian.html",
    "sha256": "b2557113c99e4fc6c9433f312db3138a77423c00521413085f6112468bc2fe3c",
    "size": 70951,
    "blob": "94c37f618758fd8738e81ef0a071425b6f0bc8c3"
  },
  "docs/research/proactive-v2/design/member-librarian.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\member-librarian.png",
    "sha256": "7ad6d5b066a6e2d0d9fa94b2db936437fea64e0c1a25f77e5e07a561646f73f4",
    "size": 370652,
    "blob": "451a66bc1791d61efe021c5221d13e733f924ab1"
  },
  "docs/research/proactive-v2/design/member-light.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\member-light.png",
    "sha256": "ca37d64de0a10c946b997d62d099142ed28663a0f6987045a0da131d7658c04d",
    "size": 329584,
    "blob": "e5c2101cc01fde380c5afafffe6c1f7c4f8e2330"
  },
  "docs/research/proactive-v2/design/member.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\member.html",
    "sha256": "3678a9d225d1b13a78cf8142d8014b8fb31e482e0df8f712b4dc8d1b6bbfd808",
    "size": 71501,
    "blob": "6a2aa9a9166a64d9540c5ddab5faf8e8d9a8df2d"
  },
  "docs/research/proactive-v2/design/member.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\member.png",
    "sha256": "12257464d7ac861964ceefa998e861b4745e30b3abcdf766ed84afb1a9211564",
    "size": 365932,
    "blob": "bd10be16b26b3fa63bf879fd50b1ff294ff3b62c"
  },
  "docs/research/proactive-v2/design/memory-light.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\memory-light.png",
    "sha256": "9de99a1ec4e7c52c4fd99225815d22adb4d5b43e2f98055055302e15feb915f0",
    "size": 120386,
    "blob": "d87c12f118ef460b5b02096300df08ce5462698f"
  },
  "docs/research/proactive-v2/design/memory.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\memory.html",
    "sha256": "1169a5b2f1139b2c1177a2c6dc8c864750559ab24082122b724ade46e0702b35",
    "size": 61089,
    "blob": "436a27e3bf3ec518530ad9504c04f3fefa395729"
  },
  "docs/research/proactive-v2/design/memory.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\memory.png",
    "sha256": "62fe45e454b4341a33807a5c71e6d881d2bf279edb6d5bfb5c155eebf79a757e",
    "size": 111540,
    "blob": "c3c2756702a868aaaee997b702f340503b8771d5"
  },
  "docs/research/proactive-v2/design/orb-faces-light.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\orb-faces-light.png",
    "sha256": "157945785dfa97eee32935579078c8ae1df0aea86792cae672117322d435e9f3",
    "size": 1986157,
    "blob": "0a515c82f8c4a4f17f4e9cbab216f4b376473d8e"
  },
  "docs/research/proactive-v2/design/orb-faces.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\orb-faces.html",
    "sha256": "462a640e5c5baeaa21cebc267e3aa84d4636d95ffd1c423b3b8114cee7c0f6f0",
    "size": 58959,
    "blob": "217b9c1f6aecafacb38114cfc5555e84c443299b"
  },
  "docs/research/proactive-v2/design/orb-faces.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\orb-faces.png",
    "sha256": "ee594dbc257f51912fc416bbe95f5b475644f5f8f19ec1dc340e676e316b4831",
    "size": 1914007,
    "blob": "746658eb8ec2cfb576a560408e03c60f8b0f5258"
  },
  "docs/research/proactive-v2/design/sources-light.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\sources-light.png",
    "sha256": "724bd7371d1bc438129df50f4d1f915d3e3557afed0c8074cb0ee3d0a1fa5411",
    "size": 128133,
    "blob": "60c75bca58bb735485351221d222bc587bf66db3"
  },
  "docs/research/proactive-v2/design/sources.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\sources.html",
    "sha256": "f41c1a3dcabed765d4e754fa3c52dc48f1177ac8a2536ec175044bd833387b02",
    "size": 60953,
    "blob": "8d91035fceebaed3ef6a093bb95f26899294fcad"
  },
  "docs/research/proactive-v2/design/sources.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\sources.png",
    "sha256": "201ee7037d9e9166f6ea9555a9f3bb32ac88f3c7fb4ead2220223cfdfff3a80f",
    "size": 153156,
    "blob": "09e36b1047d9568c60b6cd8f62533a48336bebec"
  },
  "docs/research/proactive-v2/design/strip-light.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\strip-light.png",
    "sha256": "74a0c05e1ef70137b8427fa559fe177b1843c897b7ef2c866ae39cb1ca351173",
    "size": 121723,
    "blob": "6733e0e5bbf680d8fe47a509adbea1d91662b591"
  },
  "docs/research/proactive-v2/design/strip-states.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\strip-states.html",
    "sha256": "a4fc18ec5c609b05f7688be09ca39b4a0cfdc45ac0a0e68788932550b1dca23d",
    "size": 63910,
    "blob": "4be90a8bd279eec832300b5b96e7bddf9d88537f"
  },
  "docs/research/proactive-v2/design/strip-states.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\strip-states.png",
    "sha256": "6dfeb71162be0b0d82ff9d9dc60c3926fbe8f6ca6c3e9695f041c68b7e6dafbc",
    "size": 223634,
    "blob": "373a9ad29732c41ed4a5188ce152699f8c3fc9dd"
  },
  "docs/research/proactive-v2/design/strip.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\strip.html",
    "sha256": "dbb159045670100b225b504a74c3915d12eb9f8ef8d8eb932b32881aab93f8bd",
    "size": 62562,
    "blob": "9d4f410b79b361568117d9adf8ba5e7bcba209ad"
  },
  "docs/research/proactive-v2/design/strip.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\strip.png",
    "sha256": "3710de861f6e710ddb475add1387d937ad1c24f0e369e8589028eabaddf4d97a",
    "size": 120935,
    "blob": "7e50af0bc003fd5a5b39c6d703c4b1fd3e7d2cdb"
  },
  "docs/research/proactive-v2/design/team-light.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\team-light.png",
    "sha256": "0a848f014e65fb88b13b66113c3f0992d4788c7678baf155b7c5580f489da65b",
    "size": 340346,
    "blob": "eb260e847aa480a68e0a92fe165ed023aca13f96"
  },
  "docs/research/proactive-v2/design/team.html": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\team.html",
    "sha256": "fe48828bbef1436a520ca4daf62e5f23e8e9a11e1b8ed50949a01e72c68f0d97",
    "size": 66657,
    "blob": "2b5b906d46c102e351397851af4115052115bb37"
  },
  "docs/research/proactive-v2/design/team.png": {
    "source": "C:\\Users\\SHIRA\\Documents\\AI\\Android-agent-use\\docs\\research\\proactive-v2\\design\\team.png",
    "sha256": "642e99a9b8b293b86d2e5ca91529a3f2f30f388763d0fcb42ef0f861d9be034b",
    "size": 340848,
    "blob": "b3886a15893d68b137aaa94151652e87c713bfbc"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/assistant-flow-limits-20261028/base-1179.json": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\assistant-flow-limits-20261028\\base-1179.json",
    "sha256": "e462cb7bdd912ca9eaf9f5daf385319b28ae769a8fe5473bcf6ce2f132035543",
    "size": 3264,
    "blob": "f7eb14bb17186a3de3bca7e5e7428146b30bdb1b"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/assistant-flow-limits-20261028/build.ps1": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\assistant-flow-limits-20261028\\build.ps1",
    "sha256": "4045f4c69cdbd05b8fe3fb79f538fa8b3b9cc5b0b327f1ae86e9453d5ec14d85",
    "size": 953,
    "blob": "39e7466a7b91300799641628cfb674839d8e6f04"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/assistant-flow-limits-20261028/combined-source.patch": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\assistant-flow-limits-20261028\\combined-source.patch",
    "sha256": "93c292ce37f7d6705ca6e89ed53a5cc090eda643ce836f14d04711c5fac24339",
    "size": 221895,
    "blob": "afb57e8127d1046c07a92a5b875e342a6f390dbe"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/assistant-flow-limits-20261028/inclusion-check.json": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\assistant-flow-limits-20261028\\inclusion-check.json",
    "sha256": "ade5617bb8389b4ce02e64f2921e9c091b69797df1d4fe98bcca7e398de4f94f",
    "size": 1799,
    "blob": "1b4562b5b8d4c0bb8fa0440e5a47f6bdea4b89ad"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/assistant-flow-limits-20261028/limit-feature.patch": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\assistant-flow-limits-20261028\\limit-feature.patch",
    "sha256": "cb3c8f1909ee64635e336eaf2a44f75c31019e5d9908a96e10cbeee6c0579407",
    "size": 108381,
    "blob": "4b223121a374fbfd4795c33326f42e78b705228b"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/assistant-flow-limits-20261028/source-1180.json": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\assistant-flow-limits-20261028\\source-1180.json",
    "sha256": "86231774277f995a5da6ebdd69c8d5acc30c81b6936fed73d6556c2ef43d11b8",
    "size": 7508,
    "blob": "97965c65fee421e08db195ab3f92f2161046807d"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/assistant-flow-limits-20261028/storage.gradle": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\assistant-flow-limits-20261028\\storage.gradle",
    "sha256": "6934574d5ac607528e55fbbfbdef2513c00a8c3efa9f21a5d073ef3a4d194b41",
    "size": 4075,
    "blob": "4c659c7fc7e85d56f101428801f223e705409725"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/assistant-flow-limits-20261028/verify_inclusion.py": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\assistant-flow-limits-20261028\\verify_inclusion.py",
    "sha256": "fe692cf3b78d7b8de89cc8ac50ac9b7d729848230dad286f1eb9c8812110084c",
    "size": 3546,
    "blob": "b94765c78d0189bb926d5079a3dc9018a52bc2e2"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/computer-use-approvals-20261028/apk-proof.json": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\computer-use-approvals-20261028\\apk-proof.json",
    "sha256": "2360f5885ed8b148d83b9accf51cfeb5ee992435a0a7f5561b5c1fec82a59e95",
    "size": 144,
    "blob": "1a5c67c608b61403fef64bfe63172606f36560a8"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/computer-use-approvals-20261028/baseline-1180.json": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\computer-use-approvals-20261028\\baseline-1180.json",
    "sha256": "138322128536cbdfa4a61be57096342f2a98c8ac826e25e88d4c7271bb2f4949",
    "size": 121348,
    "blob": "29efa9b07bf2efc2117c7edfbaaa3a5a6bd727fb"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/computer-use-approvals-20261028/focused-fix.patch": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\computer-use-approvals-20261028\\focused-fix.patch",
    "sha256": "7f1912b1edf5114d65de04702b0b9515fbc1dd9770fda711536d8ad503b04a3a",
    "size": 40433,
    "blob": "9f778a10fe583c29de55d07588b0da8f45b8a342"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/computer-use-approvals-20261028/source-1180.patch": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\computer-use-approvals-20261028\\source-1180.patch",
    "sha256": "276e74e2a003daa7837e5b3d247cb45303274d6bf57afafee9cb471ef158bc97",
    "size": 914316,
    "blob": "5a119596e939efc7b94bfe2983ec33c5b4544a4c"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/computer-use-approvals-20261028/source-1181.json": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\computer-use-approvals-20261028\\source-1181.json",
    "sha256": "d6c1d604a9868172651510d1d893ec00bb280ebcb9973221a31ec168df373ada",
    "size": 121868,
    "blob": "88dfa36a8d2ebea875464527ba6b2daced046543"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/computer-use-approvals-20261028/storage.gradle": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\computer-use-approvals-20261028\\storage.gradle",
    "sha256": "9684198352c38917705a9411b63413402b680ef79f051a80e48178c99a8be406",
    "size": 4076,
    "blob": "ca1a1e4433eb48619378762a4a66266f2e25100e"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/manager-eye-1178-20261027/build.ps1": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\manager-eye-1178-20261027\\build.ps1",
    "sha256": "860666ebfdd755c2e9a3657b148f9a2ba6fb83cfcca2f2ea3dab7d43a517b9ee",
    "size": 729,
    "blob": "bc2fd29ace4d629d8c3c444e752dd6203069050a"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/manager-eye-1178-20261027/source1177-manifest.json": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\manager-eye-1178-20261027\\source1177-manifest.json",
    "sha256": "bcff3f6b8528770e6bc42c193bd7dadf5d19743815aeb350c7a76504eb0d598b",
    "size": 94574,
    "blob": "36cb6e90150c1d0eb28476bc912590155b02bda4"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/manager-eye-1178-20261027/source1177.patch": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\manager-eye-1178-20261027\\source1177.patch",
    "sha256": "0766f892f5a4fe3da62c81cdc9a63fea2465742460d7dd40c3e0b3179c2e1acb",
    "size": 280902,
    "blob": "4aadf13dd0d0919c000ff0469acdcd25bd04f2ac"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/manager-plus-mike-character-20261102/compare.py": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\manager-plus-mike-character-20261102\\compare.py",
    "sha256": "4b6f0da91b60220d25d41320ac4902658201d846355b677af5124b6de15bd25e",
    "size": 706,
    "blob": "eb867faf92d30a50afe706ab6dd5a04c7d04e614"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/manager-plus-mike-character-20261102/inspect.py": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\manager-plus-mike-character-20261102\\inspect.py",
    "sha256": "6ce2b32fbdb12edec3a06999e9c138f4c614778f87426ae4ebbcf99bcab6a29d",
    "size": 977,
    "blob": "a31fff9eccbb70ab1568df1e1668d216443331b7"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/manager-plus-mike-character-20261102/isolated-build.gradle": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\manager-plus-mike-character-20261102\\isolated-build.gradle",
    "sha256": "49a03fcc0d7d70ecd44948b112667b836c8d578cddc91d32902e438664c0b97b",
    "size": 790,
    "blob": "612e435a666f439a181bc4cfbda422dc54c2b5b5"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/manager-recovery-20261102/isolated-build.gradle": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\manager-recovery-20261102\\isolated-build.gradle",
    "sha256": "dd44be24c87ade34718fc275ede444ed61ac4eb1f805162b39f5f3a62d51c3bf",
    "size": 263,
    "blob": "e33687fe86ab76a5bb2a12a28fe6af27a6098f8a"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/personal-assistant-flow-20261028/build.ps1": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\personal-assistant-flow-20261028\\build.ps1",
    "sha256": "376f5fc2879f058ca876483e0af3ef36cbc6790e2c4ba931c12d8d644be34681",
    "size": 773,
    "blob": "6f9ebae1757ad91a08954a642d69c02b784e0568"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/personal-assistant-flow-20261028/source-1178.json": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\personal-assistant-flow-20261028\\source-1178.json",
    "sha256": "3e98c56aaf052c2514a31f0ef032d60c9e832794ddfd348762ababa9e0209119",
    "size": 94688,
    "blob": "f56e12e2d39c86a80a312fda1afc9a958d2ff013"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/personal-assistant-flow-20261028/source-1179.json": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\personal-assistant-flow-20261028\\source-1179.json",
    "sha256": "e462cb7bdd912ca9eaf9f5daf385319b28ae769a8fe5473bcf6ce2f132035543",
    "size": 3264,
    "blob": "f7eb14bb17186a3de3bca7e5e7428146b30bdb1b"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/personal-profile-notifications-20261026/baseline-manifest.json": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\personal-profile-notifications-20261026\\baseline-manifest.json",
    "sha256": "52b6417d72927ad12290374d90ea9dc4724db4b5aa57a7d3500cfc60a71cfb22",
    "size": 10241,
    "blob": "e2551f39972c99380caa34513196add99bce10c8"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/personal-profile-notifications-20261026/privacy-fix.patch": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\personal-profile-notifications-20261026\\privacy-fix.patch",
    "sha256": "cc0081b5856adca3410ee96caee53fa818aa6c8d35fcc0a64129ba493fcc087b",
    "size": 29228,
    "blob": "980e7b2fffe21cc5d2db69c984b38ed2f2e3d984"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/personal-profile-notifications-20261026/storage.gradle": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\personal-profile-notifications-20261026\\storage.gradle",
    "sha256": "47993882be42c2357afba7e5f909dc9b63fdd14a94f6132fa7d4bc59e1ddc5c6",
    "size": 4082,
    "blob": "1a1aae5646305272969d1d32d90a4477c2f1544a"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/proactive-tasks/edit_coord_test.py": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\proactive-tasks\\edit_coord_test.py",
    "sha256": "71bc6d225034ab4090c942c3e19a75efde122c2fbc902c19fefc75b8ef7c2a5f",
    "size": 3124,
    "blob": "62e7bed6695aa8532230de4ec614aa7aaa6c79bf"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/proactive-tasks/edit_desk.py": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\proactive-tasks\\edit_desk.py",
    "sha256": "a6277d20c465bbb801c30c137aae3b9a562823b87b0a50456ad51d1ca55c8f2b",
    "size": 3893,
    "blob": "f3e123bcf506729633669492d4868520eb3ce449"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/tasks-assistant-unified-20261029/board-flow.patch": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\tasks-assistant-unified-20261029\\board-flow.patch",
    "sha256": "51cabcadd28bba39f993be75b6031a1e9e98c627497c2315f31882e9c3ec6d6c",
    "size": 652282,
    "blob": "517b4e59249e494224a98899b3519aa87ee2b50a"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/tasks-assistant-unified-20261029/source-snapshot.json": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\tasks-assistant-unified-20261029\\source-snapshot.json",
    "sha256": "995ccb2843ed952dea90dfa1ce0a1b33e260014f70f4e49ae84aa7b3d6e8bfde",
    "size": 13571,
    "blob": "e433daa404e1949ddf4abd69c864b63b3fb34faf"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/tasks-assistant-unified-20261029/test-summary.json": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\tasks-assistant-unified-20261029\\test-summary.json",
    "sha256": "fd19837fbf937e8600459d87bc5bba35bb10c3c6a2696b31e2f0e614f8f9348e",
    "size": 1330,
    "blob": "b0f9d62eef60a58154e371a047bcf2e2399f4aeb"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/whatsapp-widget-probe-20261027/baseline-1177.patch": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\whatsapp-widget-probe-20261027\\baseline-1177.patch",
    "sha256": "4484c3e7e8b00bf0aa86850166e77ffe12a3aa1d74b1631bda6e32afdb996acc",
    "size": 280990,
    "blob": "895e637cbf7e7cb19e13da6eafe8325072f6a405"
  },
  "docs/history/manager-local-cleanup-20261112/legacy/whatsapp-widget-probe-20261027/baseline-source-manifest.json": {
    "source": "C:\\Users\\SHIRA\\AppData\\Local\\Temp\\hey-mike\\whatsapp-widget-probe-20261027\\baseline-source-manifest.json",
    "sha256": "fab263f220260980d9587085f51e5c369b4e2f1179f60cb3c3cd720c898d54e3",
    "size": 8690,
    "blob": "0f497b82f66b0ed4ac1aec2e62461d0e58c4c1e6"
  }
}
```
