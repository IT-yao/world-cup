create table if not exists predictions (
  id text primary key,
  nickname text not null,
  match_id text not null,
  winner text not null,
  home_score int not null,
  away_score int not null,
  created_at timestamp not null
);

create unique index if not exists predictions_nickname_match_idx
  on predictions (lower(nickname), match_id);
