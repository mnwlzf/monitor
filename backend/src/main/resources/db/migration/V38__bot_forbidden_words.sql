-- ============================================================
-- V38: QQ 机器人违禁词
-- ============================================================
-- 管理员可以在页面上维护，也可以私聊机器人使用 /违禁词、/添加违禁词、/删除违禁词。
-- 用户消息（含引用内容与语音转写）命中任一词时，机器人直接拒绝回答，不调用大模型。

CREATE TABLE bot_forbidden_words
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    word       VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT bot_forbidden_words_word_not_blank CHECK (length(btrim(word)) > 0)
);

COMMENT ON TABLE bot_forbidden_words IS 'QQ 机器人违禁词；命中后拒绝回答';
COMMENT ON COLUMN bot_forbidden_words.word IS '违禁词或短语，匹配时大小写不敏感';

-- 大小写不敏感去重（中文不受影响，英文 A/A 视为同一个词）。
CREATE UNIQUE INDEX bot_forbidden_words_word_uniq ON bot_forbidden_words (lower(word));
