CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE workspaces (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    owner_id   BIGINT       NOT NULL REFERENCES users (id),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE workspace_members (
    workspace_id BIGINT      NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    user_id      BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role         VARCHAR(20) NOT NULL CHECK (role IN ('owner', 'admin', 'member')),
    joined_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id)
);

CREATE TABLE channels (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workspace_id BIGINT      NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    name         VARCHAR(80),
    type         VARCHAR(10) NOT NULL CHECK (type IN ('public', 'private', 'dm')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, name)
);

CREATE TABLE messages (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel_id        BIGINT      NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    seq               BIGINT      NOT NULL,
    user_id           BIGINT      NOT NULL REFERENCES users (id),
    client_message_id UUID        NOT NULL,
    parent_id         BIGINT      REFERENCES messages (id),
    body              TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    edited_at         TIMESTAMPTZ,
    deleted_at        TIMESTAMPTZ,
    UNIQUE (channel_id, client_message_id),
    UNIQUE (channel_id, seq)
);

CREATE INDEX idx_messages_channel_id_id ON messages (channel_id, id);
CREATE INDEX idx_messages_parent_id ON messages (parent_id);

CREATE TABLE channel_members (
    channel_id           BIGINT      NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    user_id              BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    last_read_message_id BIGINT      REFERENCES messages (id),
    joined_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (channel_id, user_id)
);

CREATE TABLE reactions (
    message_id BIGINT       NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    user_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    emoji      VARCHAR(50)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, user_id, emoji)
);

CREATE TABLE files (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id  BIGINT       NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    storage_key VARCHAR(500) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    size_bytes  BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);