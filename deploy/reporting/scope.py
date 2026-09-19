"""Bind the maintained report queries to one customer and deployment environment."""
import copy
import re


def scoped_config(config, customer, environment):
    for value in (customer, environment):
        if not isinstance(value, str) or not re.fullmatch(r"[a-zA-Z0-9_.-]{1,80}", value):
            raise ValueError("invalid_report_scope")
    result = copy.deepcopy(config)
    selector = f'customer="{customer}",environment="{environment}"'
    for query in result["queries"]:
        expression, count = re.subn(r'customer="[a-zA-Z0-9_.-]+"(?:,environment="[a-zA-Z0-9_.-]+")?',
                                    selector, query["expr"])
        if not count:
            raise ValueError("report_query_missing_customer_scope")
        query["expr"] = expression
    matchers = [item for item in result.get("alert_matchers", [])
                if not item.startswith(("customer=", "environment="))]
    result.update(customer=customer, environment=environment,
                  alert_matchers=[f'customer="{customer}"', f'environment="{environment}"', *matchers])
    return result
