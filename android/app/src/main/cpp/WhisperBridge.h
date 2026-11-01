#pragma once
#include <string>
#include <vector>
std::string transcribe_clip(const std::string& model, const std::vector<float>& samples, const std::string& language);
