SELECT Person1Id AS personId
  FROM personNumFriendsOfFriendsOfFriends
 WHERE numFriends > 0
   AND deletionDate > '2019-01-01'::TIMESTAMP
   AND creationDate < :date_limit_filter::TIMESTAMP
 ORDER BY md5(Person1Id::VARCHAR)
LIMIT 100
