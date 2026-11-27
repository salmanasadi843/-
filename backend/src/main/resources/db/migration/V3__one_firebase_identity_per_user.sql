-- One internal account may have only one Firebase identity.
-- Other identity providers can be added later without changing this invariant.
CREATE UNIQUE INDEX ux_auth_identities_firebase_user
    ON auth_identities(user_id)
    WHERE provider = 'firebase';
