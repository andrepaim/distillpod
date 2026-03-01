"""
Unit tests for garmin-price-watch-v2.py price extraction.
"""
import sys
import os
import importlib.util

# Load the script as a module without executing __main__
SCRIPT = "/home/andrepaim/.openclaw/workspace/scripts/garmin-price-watch-v2.py"
spec = importlib.util.spec_from_file_location("garmin_price", SCRIPT)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

extract_prices = mod.extract_prices
PRICE_MIN = mod.PRICE_MIN   # 1600.0


class TestExtractPrices:

    def test_parses_brl_dot_comma(self):
        assert extract_prices("Melhor preço R$1.750,00") == [1750.0]

    def test_parses_brl_dot_no_cents(self):
        assert extract_prices("a partir de R$1.750") == [1750.0]

    def test_parses_brl_no_dot_comma(self):
        assert extract_prices("R$1750,00") == [1750.0]

    def test_parses_brl_plain_integer(self):
        assert extract_prices("por R$1790") == [1790.0]

    def test_parses_multiple_prices(self):
        text = "R$1.790 ou R$2.099,00 ou R$3.599"
        result = extract_prices(text)
        assert 1790.0 in result
        assert 2099.0 in result
        assert 3599.0 in result

    def test_returns_sorted(self):
        text = "R$2.500 e R$1.800"
        result = extract_prices(text)
        assert result == sorted(result)

    def test_filters_below_price_min(self):
        # R$1.249 is a used unit — should be excluded
        result = extract_prices("R$1.249,00")
        assert result == []

    def test_filters_above_4000(self):
        result = extract_prices("R$4.500,00")
        assert result == []

    def test_price_min_boundary_excluded(self):
        # Exactly at PRICE_MIN - 1 → excluded
        result = extract_prices(f"R${PRICE_MIN - 1:.0f}")
        assert result == []

    def test_price_min_boundary_included(self):
        # Exactly at PRICE_MIN → included
        result = extract_prices(f"R${PRICE_MIN:.0f}")
        assert PRICE_MIN in result

    def test_garbage_input(self):
        assert extract_prices("no prices here at all") == []

    def test_empty_string(self):
        assert extract_prices("") == []

    def test_deduplicates(self):
        text = "R$1.790 e também R$1.790"
        result = extract_prices(text)
        assert result.count(1790.0) == 1

    def test_real_world_ml_snippet(self):
        text = (
            "Smartwatch Relógio Garmin Forerunner 165 43mm Preto. "
            "Melhor preço. R$1.987. Chegará amanhã. "
            "Ver mais opções a partir de R$2.079."
        )
        result = extract_prices(text)
        assert 1987.0 in result
        assert 2079.0 in result
        assert min(result) == 1987.0
