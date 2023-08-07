# JiraFilter
JiraのREST APIを使って特定ユーザが関わった(creator/reporter/assigner)チケットを更新日順に列挙するスクリプト。

# ビルド手順
please try 
```sh
./deploy.sh && java -jar ./JiraFilter.jar -h -v
```

# JiraのAPIトークンの取得
- Jiraの右上のアカウント画像をタップ
- 「アカウントを管理」をタップ
- 認証をすませる
- 「セキュリティ」タブをタップ
- 「APIトークンの作成と管理」をタップ
- 「APIトークンを作成する」 をタップ
- ラベル名を入力。適当で良い。例えばJiraFilterとか。
- 新しいAPIトークンがHidden表示されるので、コピーする。
- コピーした内容を適当にメモしておく

# secrets.json の用意
```
cp secrets.json.sample secrets.json
chmod 600 secrets.json
emacs  secrets.json
```

以下の項目を書き換えること：

|key|desc|example|
|---|---|---|
|server|対象組織のサーバ名|example.atlassian.net|
|user|Jiraログインユーザのメールアドレス|user@mail.address|
|apiToken|上の章で取得したAPIトークン|******************|


# 起動
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
- `-u` の指定は必須です。 抽出したいユーザのdisplayNameの全部または一部を指定します。
- `-p` にはカンマ区切りで対象プロジェクトのキーを列挙します。例: `-p TSD,HAL`
- `--epic` にはカンマ区切りでサブタスクの親のissueKeyOrName を指定します。例: `--epic TSD-3626,HAL-4048`

うまく動作すれば`-d` で指定した日数以内のチケットが更新日順に出力されます。
