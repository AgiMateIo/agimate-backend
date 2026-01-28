package ru.agimate.connectorsapi.connector;

import lombok.Getter;

@Getter
public enum ConnectorMethodCategory {
    PRODUCTS("Товары"),
    ORDERS("Заказы"),
    ANALYTICS("Аналитика"),
    PRICES("Цены"),
    STOCKS("Остатки"),
    FBS("FBS"),
    FBO("FBO"),
    OTHER("Другое");

    private final String displayName;

    ConnectorMethodCategory(String displayName) {
        this.displayName = displayName;
    }
}
