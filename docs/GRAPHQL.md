## Overview


Against the Backend, the client will use GraphQL with these parameters. Always besure to pass along the "Authorization" header. 
```
:post "/api"
:body "<body>"
:headers {"Content-Type" "application/json"
          "Authorization" "Bearer <your-token>"}
```

### Login

Depending on whether the user already exists, you'll get one of two responses.
```
body: {"query": "mutation Login { login { message }} "}
response: {"data":{"login":{"message":"useradded"}}}

body: {"query": "mutation Login { login { message }} "}
response: {"data":{"login":{"message":"userexists"}}}
```

### Examples

For example GraphQL use case call and responses, see the `docs/graphql` [directory](docs/graphql).
