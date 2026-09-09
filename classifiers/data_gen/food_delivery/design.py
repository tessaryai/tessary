# SPDX-License-Identifier: Apache-2.0
"""Static design for the ZipEats food-delivery support agent: the reference corpus's fixture.

Everything here is descriptive, not executable behavior: the tool catalogue, RAG collections, and
intent mix are handed to Claude Haiku as generation instructions (see ``generate.py``) and to the
OTel emitter as the vocabulary of span names/attributes (see ``emit_otel.py``). The SOP itself
lives in ``classifiers/data/food_delivery/sop.md``; this module mirrors its tool/collection names
so the three stay consistent.
"""

from __future__ import annotations

AGENT_NAME = "zipeats-support-agent"
COMPANY_NAME = "ZipEats"

TOOLS: dict[str, str] = {
    "get_order_details": (
        "(order_id) -> {order_id, customer_id, restaurant_name, items:[{name,qty,price}], "
        "subtotal, delivery_fee, tax, total, payment_method, placed_at, promised_delivery_time, "
        "status}. Always the first call once an order id is known."
    ),
    "get_delivery_status": (
        "(order_id) -> {order_id, status: placed|confirmed|preparing|rider_assigned|picked_up|"
        "out_for_delivery|delivered|delayed|cancelled, eta_minutes, last_update_at}."
    ),
    "get_rider_location": (
        "(order_id) -> {order_id, rider_name, lat, lng, distance_to_customer_km, last_updated}. "
        "Only meaningful once status is rider_assigned or later."
    ),
    "contact_rider": (
        "(order_id, message) -> {order_id, rider_replied: bool, rider_reply, "
        "response_time_seconds}. Can time out / fail on first attempt; a retry is normal."
    ),
    "contact_restaurant": (
        "(order_id, message) -> {order_id, restaurant_replied: bool, restaurant_reply}. "
        "Can time out / fail on first attempt; a retry is normal."
    ),
    "check_payment_status": (
        "(order_id) -> {order_id, payment_state: captured|pending|failed|refunded|"
        "partially_refunded, charge_count, amount_charged}."
    ),
    "issue_refund": (
        "(order_id, amount, reason_code) -> {refund_id, status: approved|pending_review, "
        "eta_business_days}. Refund to original payment method."
    ),
    "issue_account_credit": (
        "(customer_id, amount, reason_code) -> {credit_id, new_wallet_balance}. Wallet credit, "
        "reflects immediately."
    ),
    "cancel_order": (
        "(order_id, reason_code) -> {order_id, cancellation_status: cancelled|"
        "not_cancellable_already_out_for_delivery, fee_waived: bool}."
    ),
    "raise_complaint_ticket": (
        "(order_id, category, description) -> {ticket_id, priority: low|medium|high, "
        "sla_hours}."
    ),
    "check_account_standing": (
        "(customer_id) -> {customer_id, refund_count_30d, is_flagged_for_abuse, tenure_months, "
        "loyalty_tier}. Called before any refund/credit/fee-waiver authorization."
    ),
    "apply_promo_code": (
        "(order_id, promo_code) -> {applied: bool, discount_amount, failure_reason: expired|"
        "minimum_order_not_met|restaurant_excluded|already_redeemed|new_customer_only|"
        "stacking_not_allowed|none}."
    ),
    "get_subscription_status": (
        "(customer_id) -> {plan: none|zip_plus_monthly|zip_plus_annual, status, "
        "next_billing_date, last_charge_amount}."
    ),
    "escalate_to_specialist": (
        "(reference_id, reason) -> {escalation_id, queue, eta_minutes}. Last resort per SOP-10."
    ),
}

RAG_COLLECTIONS: dict[str, str] = {
    "refund_policy": "Refund eligibility, amounts, original-payment-method timelines, abuse guardrail.",
    "cancellation_policy": "Cancellation windows by delivery status, fees, restaurant-initiated cancellations.",
    "delivery_fee_policy": "Late-delivery thresholds and credit bands, rider-outreach expectation.",
    "packaging_and_quality_policy": "Food-quality/packaging complaint credit bands and ticket logging.",
    "missing_item_procedure": "Missing vs. wrong item definitions, item-level refund/credit steps.",
    "subscription_terms": "ZipEats+ plan billing, renewal, and cancellation-effective-date rules.",
    "promo_terms": "Promo code failure reasons and how to explain/resolve each.",
    "payment_dispute_policy": "Payment-failure, duplicate-charge, and pending-payment diagnosis and remedies.",
}

INTENTS: dict[str, dict] = {
    "order_not_delivered": {
        "weight": 0.13,
        "summary": "Order shows delivered (or long overdue) but the customer never received it.",
        "typical_tools": [
            "get_order_details",
            "get_delivery_status",
            "get_rider_location",
            "contact_rider",
            "check_account_standing",
            "issue_refund",
            "raise_complaint_ticket",
        ],
        "typical_rag": ["refund_policy"],
    },
    "missing_or_wrong_items": {
        "weight": 0.12,
        "summary": "One or more items are missing from the order, or the wrong item was delivered.",
        "typical_tools": [
            "get_order_details",
            "check_account_standing",
            "issue_refund",
            "issue_account_credit",
            "raise_complaint_ticket",
        ],
        "typical_rag": ["missing_item_procedure"],
    },
    "late_delivery_credit": {
        "weight": 0.11,
        "summary": "Delivery is significantly later than promised; customer wants a delivery-fee credit or explanation.",
        "typical_tools": [
            "get_order_details",
            "get_delivery_status",
            "get_rider_location",
            "contact_rider",
            "issue_account_credit",
        ],
        "typical_rag": ["delivery_fee_policy"],
    },
    "cancellation_request": {
        "weight": 0.1,
        "summary": "Customer wants to cancel an order that has already been placed.",
        "typical_tools": ["get_order_details", "get_delivery_status", "cancel_order"],
        "typical_rag": ["cancellation_policy"],
    },
    "payment_failed_or_double_charge": {
        "weight": 0.1,
        "summary": "Customer reports a failed payment or being charged more than once for one order.",
        "typical_tools": [
            "get_order_details",
            "check_payment_status",
            "issue_refund",
            "cancel_order",
        ],
        "typical_rag": ["payment_dispute_policy"],
    },
    "rider_behavior_complaint": {
        "weight": 0.08,
        "summary": "Customer reports a rider being rude, unsafe, or otherwise behaving badly.",
        "typical_tools": [
            "get_order_details",
            "get_delivery_status",
            "contact_rider",
            "raise_complaint_ticket",
            "escalate_to_specialist",
        ],
        "typical_rag": [],
    },
    "promo_not_applied": {
        "weight": 0.09,
        "summary": "A promo/discount code the customer tried did not apply to their order.",
        "typical_tools": [
            "get_order_details",
            "apply_promo_code",
            "issue_account_credit",
            "raise_complaint_ticket",
        ],
        "typical_rag": ["promo_terms"],
    },
    "subscription_billing_issue": {
        "weight": 0.09,
        "summary": "Customer disputes a ZipEats+ subscription charge or wants to change/cancel their plan.",
        "typical_tools": ["get_subscription_status", "issue_refund", "check_account_standing"],
        "typical_rag": ["subscription_terms"],
    },
    "restaurant_closed_or_unavailable": {
        "weight": 0.08,
        "summary": "The restaurant cannot fulfill the order after it was placed (closed, out of stock, overloaded).",
        "typical_tools": [
            "get_order_details",
            "get_delivery_status",
            "contact_restaurant",
            "cancel_order",
            "issue_refund",
        ],
        "typical_rag": ["cancellation_policy", "refund_policy"],
    },
    "food_quality_complaint": {
        "weight": 0.1,
        "summary": "Food arrived but cold, spilled, poorly packaged, or otherwise below quality expectations.",
        "typical_tools": [
            "get_order_details",
            "check_account_standing",
            "issue_account_credit",
            "raise_complaint_ticket",
        ],
        "typical_rag": ["packaging_and_quality_policy"],
    },
}

PERSONAS: list[dict] = [
    {"id": "terse_business", "tone": "terse, impatient, wants a fast answer, minimal pleasantries"},
    {"id": "polite_detailed", "tone": "polite, gives lots of context up front, thorough"},
    {"id": "frustrated_repeat", "tone": "frustrated, mentions this has happened before, needs de-escalation"},
    {"id": "anxious_first_time", "tone": "anxious, unfamiliar with the app, needs things explained simply"},
    {"id": "friendly_casual", "tone": "friendly, casual, uses emoji/slang, easygoing about the issue"},
    {"id": "formal_precise", "tone": "formal, precise, quotes order numbers and times exactly"},
    {"id": "elderly_uncertain", "tone": "older customer, unsure of app terminology, needs patient guidance"},
    {"id": "student_budget", "tone": "budget-conscious student, cares a lot about the refund/credit amount"},
    {"id": "busy_parent", "tone": "distracted, multitasking, short replies, cares about resolution speed"},
    {"id": "loyal_regular", "tone": "long-time loyal customer, references their order history, expects recognition"},
]

RAG_SUMMARIES: dict[str, str] = {
    "refund_policy": (
        "Full refund to original payment method is agent-authorizable once a delivery/payment "
        "failure is confirmed by tool data. Refunds take 3-7 business days; wallet credits are "
        "immediate. If check_account_standing shows is_flagged_for_abuse or more than 3 refunds "
        "in 30 days, do not self-authorize — escalate instead."
    ),
    "cancellation_policy": (
        "Before restaurant acceptance (status placed/confirmed): free cancellation, full refund. "
        "After acceptance, before pickup (preparing/rider_assigned): cancellable with a 50% "
        "food-subtotal prep fee, waivable once per 90 days as goodwill. After pickup "
        "(picked_up/out_for_delivery): not cancellable — redirect to the delivery-issue flow. "
        "Restaurant-initiated cancellations are always a full refund regardless of window."
    ),
    "delivery_fee_policy": (
        "Late = actual or elapsed time exceeds promised_delivery_time by more than 15 minutes. "
        "15-30 min late: full delivery-fee credit. 30-60 min late: delivery-fee credit plus a 10% "
        "food-subtotal courtesy credit. Over 60 min late or never arrives: treat as "
        "order-not-delivered under refund_policy (full refund), not a partial credit."
    ),
    "packaging_and_quality_policy": (
        "Confirmed packaging/quality issue: 50-100% credit of the affected item(s), scaled to "
        "severity; full-order credit if the entire order is inedible/spilled. No photo proof "
        "required — the complaint description is the record. Always log a ticket (category "
        "food_quality or packaging); priority high only for a health/safety concern."
    ),
    "missing_item_procedure": (
        "Confirm the reported gap against get_order_details, then refund or credit (customer's "
        "choice) that item's price. If the missing/wrong item is over half the order's value, "
        "consider a full-order remedy instead of item-level. Always raise a complaint ticket "
        "(category missing_item or wrong_item)."
    ),
    "subscription_terms": (
        "Monthly/annual plans auto-renew; cancellation takes effect at the END of the current "
        "billing period, no partial-month refund — except the annual plan gets a pro-rated "
        "refund if cancelled within 14 days of that charge. A renewal charged after the customer "
        "had already cancelled before the renewal date is a billing error: full refund."
    ),
    "promo_terms": (
        "apply_promo_code failure reasons: expired, minimum_order_not_met, restaurant_excluded, "
        "already_redeemed, new_customer_only, stacking_not_allowed. Explain the actual reason "
        "returned. If a retry shows applied=true, no further compensation is owed. If it should "
        "have worked per its terms but still fails, issue an equivalent account credit and log a "
        "promo_defect ticket."
    ),
    "payment_dispute_policy": (
        "Always call check_payment_status before responding to a payment complaint. charge_count "
        "> 1 for one order is a confirmed duplicate: refund the extra charge(s). Payment failed "
        "but order shows placed: cancel with no fee, advise retry. Pending under 24h is normal, "
        "explain the settlement window; pending over 24h: escalate to a specialist."
    ),
}

# Synthetic. Do not put a person's name here: this module is public, so every literal in it
# is a public string.
DEPLOYMENT_ENVIRONMENT = "local-run-1"
