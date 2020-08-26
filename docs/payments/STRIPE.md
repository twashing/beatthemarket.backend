# Subscription Payments using Stripe


## Overview


## Stripe's System

This [blog post](https://www.baeldung.com/java-stripe-api) has a good outline of the general workflow with Stripe's payment system. It generally works like this.

The charge of the credit card will be done in five simple steps, involving the front-end (run in a browser), back-end (our Spring Boot application), and Stripe:

1. A user goes to the checkout page and clicks “Pay with Card”.
2. A user is presented with Stripe Checkout overlay dialog, where fills the credit card details.
3. A user confirms with “Pay <amount>” which will:
  i. Send the credit card to Stripe
  ii. Get a token in the response which will be appended to the existing form
4. Submit that form with the amount, public API key, email, and the token to our back-end
5. Our back-end contacts Stripe with the token, the amount, and the secret API key.
6. Back-end checks Stripe response and provide the user with feedback of the operation.


## How subscriptions work

Stripe's documentation has more details on **"How subscriptions work"** ([here](https://stripe.com/docs/billing/subscriptions/overview) and [here](https://stripe.com/docs/api/subscriptions))
