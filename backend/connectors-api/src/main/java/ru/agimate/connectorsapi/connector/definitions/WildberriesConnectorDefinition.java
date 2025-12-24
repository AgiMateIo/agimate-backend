package ru.agimate.connectorsapi.connector.definitions;

import org.springframework.stereotype.Component;
import ru.agimate.connectorsapi.connector.ConnectorDefinition;
import ru.agimate.connectorsapi.connector.ConnectorMethod;
import ru.agimate.connectorsapi.connector.ConnectorMethodCategory;
import ru.agimate.connectorsapi.connector.ConnectorMethodParameter;

import java.util.List;
import java.util.Map;

@Component
public class WildberriesConnectorDefinition implements ConnectorDefinition {

    @Override
    public String getConnectorCode() {
        return "wildberries";
    }

    @Override
    public List<String> getRequiredCredentialFields() {
        return List.of("apiKey");
    }

    @Override
    public List<ConnectorMethod> getMethods() {
        return List.of(
                new ConnectorMethod(
                        "getCards",
                        "Получить карточки товаров",
                        "Возвращает список карточек товаров продавца",
                        "POST",
                        "/content/v2/get/cards/list",
                        ConnectorMethodCategory.PRODUCTS,
                        List.of(
                                new ConnectorMethodParameter(
                                        "limit", "Лимит", "Количество карточек на странице",
                                        "integer", false, 100, Map.of("min", 1, "max", 1000)
                                ),
                                new ConnectorMethodParameter(
                                        "cursor", "Курсор", "Курсор для пагинации",
                                        "object", false, null, Map.of()
                                )
                        )
                ),
                new ConnectorMethod(
                        "getOrders",
                        "Новые заказы",
                        "Возвращает список новых заказов",
                        "GET",
                        "/api/v3/orders/new",
                        ConnectorMethodCategory.ORDERS,
                        List.of()
                ),
                new ConnectorMethod(
                        "getOrdersStatus",
                        "Статусы заказов",
                        "Возвращает статусы заказов по их идентификаторам",
                        "POST",
                        "/api/v3/orders/status",
                        ConnectorMethodCategory.ORDERS,
                        List.of(
                                new ConnectorMethodParameter(
                                        "orders", "ID заказов", "Массив идентификаторов заказов",
                                        "array", true, null, Map.of()
                                )
                        )
                ),
                new ConnectorMethod(
                        "getStocks",
                        "Остатки на складах",
                        "Возвращает остатки товаров на складах WB",
                        "GET",
                        "/api/v3/stocks/{warehouseId}",
                        ConnectorMethodCategory.STOCKS,
                        List.of(
                                new ConnectorMethodParameter(
                                        "warehouseId", "ID склада", "Идентификатор склада",
                                        "integer", true, null, Map.of()
                                )
                        )
                ),
                new ConnectorMethod(
                        "updateStocks",
                        "Обновить остатки",
                        "Обновляет остатки товаров на складе",
                        "PUT",
                        "/api/v3/stocks/{warehouseId}",
                        ConnectorMethodCategory.STOCKS,
                        List.of(
                                new ConnectorMethodParameter(
                                        "warehouseId", "ID склада", "Идентификатор склада",
                                        "integer", true, null, Map.of()
                                ),
                                new ConnectorMethodParameter(
                                        "stocks", "Остатки", "Массив объектов с остатками",
                                        "array", true, null, Map.of()
                                )
                        )
                ),
                new ConnectorMethod(
                        "updatePrices",
                        "Обновить цены",
                        "Загружает новые цены на товары",
                        "POST",
                        "/public/api/v1/prices",
                        ConnectorMethodCategory.PRICES,
                        List.of(
                                new ConnectorMethodParameter(
                                        "prices", "Цены", "Массив объектов с ценами (nmId, price)",
                                        "array", true, null, Map.of()
                                )
                        )
                )
        );
    }
}
