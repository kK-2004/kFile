package com.kk.geoip;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class GeoInfo {
    private String country;
    private String province;
    private String city;
}

