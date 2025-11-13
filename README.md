# Firefly III Insight Exporter

This Java application connects to the Firefly III API to extract financial insights (income, expenses, transfers) 
and writes them into an `.ods` spreadsheet. It supports OAuth2 authentication and allows filtering by year and month.

## Features

- Connects to Firefly III using OAuth2.
- Automatically refreshes access tokens.
- Extracts insights for:
    - Income categories
    - Expense categories
    - Transfer categories
- Writes data into a spreadsheet (`.ods`) organized by year and month.
- Supports flexible date filtering via command-line arguments.

---

## Getting Started

### 1. Create an OAuth2 Client

Follow the official Firefly III guide to create an OAuth2 client: https://docs.firefly-iii.org/how-to/firefly-iii/features/api/

### 2. Obtain the Authorization Code

Open the following URL in your browser (replace placeholders):

```
https://<base_url>/oauth/authorize?response_type=code&client_id=<client_id>&redirect_uri=<redirect_uri>&scope=&state=
```

After logging in, you'll be redirected to the `redirect_uri` with a `code` parameter in the URL. Copy this code, 
but remember that it will expire in 30 seconds.

---

## Configuration

Create two configuration files:

### `config.properties`

```properties
client_id=your_client_id
client_secret=your_client_secret
redirect_uri=redirect_uri
firefly_iii_base_url=https://your.firefly.instance
spreadsheet_full_path=/path/to/your/spreadsheet.ods
account_id=123  # Optional: Firefly account ID
skip_ssl_validation=false
```

### `secrets.properties`

This file will be automatically created after the first successful authentication and will store the `refresh_token`.

---

## Usage

### Command-Line Parameters

| Parameter | Description |
|----------|-------------|
| `--code` | (Optional) One-time authorization code (required only the first time) |
| `--year` | (Optional) Year to extract data for |
| `--month`| (Optional) Month to extract data for |

### Behavior

- If **only `year`** is provided:
    - If it's a **past year**, all 12 months are processed.
    - If it's the **current year**, only months up to the current month are processed.
- If **both `year` and `month`** are provided:
    - Only that specific month is processed.
- If **only `month`** is provided:
    - The current year is assumed.
- If **no parameters** are provided:
    - The current year up to the current month is processed.

### Example

```bash
java -jar firefly-iii-serenity.jar --code=abc123 --year=2024
```

---

## Output

The `.ods` spreadsheet must contain a sheet named after the year (e.g., `2025`). 
The first column (A) should list the categories. The application will fill in the corresponding cells 
with the monthly values.

### Limitation

The ODF Toolkit doesn't recalculates formulas involving the cell that is edited, so you have to do it manually, eg. 
by selecting all the cell and press F9.
F9 recalculates the selected formula or cell content.
If you have a formula in a cell and you select it, pressing F9 will force that formula to be recalculated immediately.

---

## SSL Validation

If you're using a self-signed certificate or testing locally, you can disable SSL validation by setting:

```properties
skip_ssl_validation=true
```

> ⚠️ Not recommended for production environments.

---

## Compilation

```shell
mvn clean package
```

In the `target` directory you will find the shaded jar with all the dependencies ready to be run.

## Dependencies

- Java 17 or greater
- OkHttp
- Jackson
- Apache Commons CLI
- ODF Toolkit
- Firefly III OpenAPI Client

## Disclaimer

This project is an independent work and is **not affiliated with, endorsed by, or sponsored by Firefly III** or its maintainers.  
The name "Firefly III", its logo, and any related trademarks are the property of their respective owners.  
This project does not claim any ownership over those assets and uses them only for identification purposes where applicable.
