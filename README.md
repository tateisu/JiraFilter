# JiraFilter
JiraのREST APIを使って特定ユーザが関わった(creator/reporter/assigner)チケットを更新日順に列挙するスクリプト。

----
## 行うこと
- `-p prj[,…]` で指定したJira Project を更新日順に列挙
- `--epic parent[,…]` で指定したチケットのsubtaskを列挙
- `-u name` で指定した名前(の一部)をcreator, reporter, assignee に含まないチケットを除外
- 得られたチケットを更新日順にTSV形式で列挙する

----
## JiraのAPIトークンの取得
- Jiraの右上のアカウント画像をタップ
- 「アカウントを管理」をタップ
- 認証をすませる
- 「セキュリティ」タブをタップ
- 「APIトークンの作成と管理」をタップ
- 「APIトークンを作成する」 をタップ
- ラベル名を入力。適当で良い。例えばJiraFilterとか。
- 新しいAPIトークンがHidden表示されるので、コピーする。
- コピーした内容を適当にメモしておく

## secrets.json の用意
```
cp secrets.json.sample secrets.json
chmod 600 secrets.json
emacs secrets.json
```

以下の項目を書き換えること：

|key|desc|example|
|---|---|---|
|server|対象組織のサーバ名|example.atlassian.net|
|user|Jiraログインユーザのメールアドレス|user@mail.address|
|apiToken|上の章で取得したAPIトークン|******************|

----
## ビルド手順
```sh
./deploy.sh && java -jar ./JiraFilter.jar -h -v
```

----
## 起動
```
Usage:
  java -jar JiraFilter.jar [options…]

Options:
  -h | --help
    show this help.
    default value is false
  -v | --verbose
    verbose console log.
    default value is false
  -s | --secret jsonFile
    JSON file that contains server, user, apiToken. see also: secrets.json.sample .
    default value is secrets.json
  -d | --days (number)
    Do not retrieve/display data older than the specified number of days
    default value is 20
  -u | --userName name
    specify part of the displayName that related to user of ticket
  -p | --project project[,…]
    comma-separated list of projects name/key to specified in JQL.
  --epic | -s | --optSubtaskParents issueIdOrKey[,…]
    comma-separated list of parent of subtask.
```

- `-s file` はデフォルトでカレントディレクトリの `secrets.json` が使われます。
- `-u name` の指定は必須です。 抽出したいユーザのdisplayNameの全部または一部を指定します。
- `-p prj[,…]` にカンマ区切りで対象プロジェクトのキーを列挙します。
  - 例: `-p TSD,HAL`
- `--epic issueKey[,…]` にカンマ区切りでサブタスクの親のissueKeyOrName を指定します。
  - 例: `--epic TSD-3626,HAL-4048`

うまく動作すれば`-d days` で指定した日数以内のチケットが更新日順に出力されます。
