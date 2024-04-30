-- We take a sample of 100 Messages for the short queries using the MessageId attribute
-- We do not perform ordering due to the high number of messages, which would make this an expensive operation
(
    SELECT id AS MessageId
    FROM Comment
    WHERE deletionDate > epoch_ms('2019-01-01'::TIMESTAMP)
    USING SAMPLE 50
)
UNION ALL
(
    SELECT id AS MessageId
    FROM Post
    WHERE deletionDate > epoch_ms('2019-01-01'::TIMESTAMP)
    USING SAMPLE 50
)
