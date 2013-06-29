# --- !Ups

create table "login_identity" ("id" BIGINT GENERATED BY DEFAULT AS IDENTITY(START WITH 1) NOT NULL PRIMARY KEY,"userId" VARCHAR NOT NULL,"providerId" VARCHAR NOT NULL,"firstName" VARCHAR NOT NULL,"lastName" VARCHAR NOT NULL,"fullName" VARCHAR NOT NULL,"email" VARCHAR,"avatarUrl" VARCHAR,"authMethod" VARCHAR NOT NULL,"token" VARCHAR,"secret" VARCHAR,"accessToken" VARCHAR,"tokenType" VARCHAR,"expiresIn" INTEGER,"refreshToken" VARCHAR);

# --- !Downs

drop table "login_identity";

