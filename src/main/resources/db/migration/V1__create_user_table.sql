-- user_account, not "user": reserved PostgreSQL keyword.
CREATE TABLE "user_account" (
    id UUID PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Opaque auth tokens (US-16): only the SHA-256 hash is stored.
CREATE TABLE "auth_tokens" (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES "user_account"(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    kind TEXT NOT NULL CHECK (kind IN ('access', 'refresh')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX auth_tokens_user_id_idx ON "auth_tokens"(user_id);
