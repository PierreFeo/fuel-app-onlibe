package ru.fueltracker.app.data.remote

/** Ответы сервера в точности по примерам из 04_API_CONTRACT.md. */
object Fixtures {

    const val CAR_ID = "8a3c1f2e-0b5d-4c6e-9f7a-1b2c3d4e5f60"
    const val SHEET_ID = "5d2e8f1a-3c4b-4a6d-8e9f-0a1b2c3d4e5f"
    const val REFUELING_ID = "c1d2e3f4-a5b6-4c7d-8e9f-0a1b2c3d4e5f"

    val user = """{ "id": "0f9e8d7c-6b5a-4938-2716-05f4e3d2c1b0", "phone": "+79991234567", "name": null }"""

    val tokens = """
        {
          "access_token": "access.jwt", "refresh_token": "refresh.jwt",
          "token_type": "bearer", "expires_in_sec": 900,
          "user": $user,
          "is_new_user": true
        }
    """

    val car = """
        {
          "id": "$CAR_ID", "name": "Lada Vesta", "plate_number": "А123ВС77",
          "fuel_type": "AI95", "tank_capacity_l": "50.00",
          "norm_l_per_100km": "10.068", "norm_winter_l_per_100km": null,
          "is_archived": false, "created_at": "2026-10-02T08:15:00Z"
        }
    """

    val refueling = """
        {
          "id": "$REFUELING_ID", "sheet_id": "$SHEET_ID", "refueled_at": "2026-10-05",
          "liters": "40.00", "price_per_liter": "55.00", "total_cost": "2200.00",
          "odometer_km": 52610, "station": "Лукойл, Ленина 1",
          "payment_type": "PERSONAL", "note": null
        }
    """

    /** Закрытый лист: все поля calc заполнены, есть предупреждение. */
    val closedSheet = """
        {
          "id": "$SHEET_ID", "car_id": "$CAR_ID", "year": 2026, "month": 10,
          "status": "CLOSED",
          "odometer_start_km": 52340, "odometer_end_km": 53340,
          "fuel_start_l": "12.00", "fuel_end_actual_l": "10.00",
          "season": "WINTER",
          "norm_l_per_100km": "11.684",
          "refuelings": [ $refueling ],
          "calc": {
            "refueled_l": "40.00",
            "refueled_cost": "2200.00",
            "fuel_available_l": "52.00",
            "mileage_km": 1000,
            "norm_consumption_l": "116.84",
            "fuel_end_calc_l": "-64.84",
            "fuel_end_l": "10.00",
            "actual_consumption_l": "42.00",
            "actual_l_per_100km": "4.200",
            "consumption_status": "NORMAL",
            "deviation_l": "-74.84",
            "cost_per_km": "2.20",
            "warnings": [
              { "code": "FUEL_END_NEGATIVE", "message": "Расчётный остаток отрицательный — проверьте пробег и заправки" }
            ]
          },
          "closed_at": "2026-10-31T18:00:00Z", "created_at": "2026-10-01T08:00:00Z",
          "updated_at": "2026-10-31T18:00:00Z"
        }
    """

    /** Открытый лист из примера контракта: пробег на конец не введён, большая часть calc — null. */
    val openSheet = """
        {
          "id": "$SHEET_ID", "car_id": "$CAR_ID", "year": 2026, "month": 10,
          "status": "OPEN",
          "odometer_start_km": 52340, "odometer_end_km": null,
          "fuel_start_l": "12.00", "fuel_end_actual_l": null,
          "season": "SUMMER",
          "norm_l_per_100km": "10.068",
          "refuelings": [],
          "calc": {
            "refueled_l": "80.00",
            "refueled_cost": "4400.00",
            "fuel_available_l": "92.00",
            "mileage_km": null,
            "norm_consumption_l": null,
            "fuel_end_calc_l": null,
            "fuel_end_l": null,
            "actual_consumption_l": null,
            "actual_l_per_100km": null,
            "consumption_status": null,
            "deviation_l": null,
            "cost_per_km": null,
            "warnings": []
          },
          "closed_at": null, "created_at": "2026-10-01T08:00:00Z", "updated_at": "2026-10-01T08:00:00Z"
        }
    """

    fun error(code: String, message: String, details: String = "{}") =
        """{ "error": { "code": "$code", "message": "$message", "details": $details } }"""
}
