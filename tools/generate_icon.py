import os
from PIL import Image, ImageDraw, ImageFilter

def create_modern_bus_canvas(size=512, is_round=False):
    scale = 4
    W = size * scale
    H = size * scale
    
    # 渐变背景 (深邃科技蓝 -> 极光青)
    bg = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    bg_draw = ImageDraw.Draw(bg)
    
    c1 = (18, 42, 115)    # Deep Royal Blue
    c2 = (37, 99, 235)    # Electric Blue
    c3 = (6, 182, 212)    # Cyan / Teal Accent
    
    for y in range(H):
        for x in range(0, W, 8):
            ratio = (x + y) / (W + H)
            if ratio < 0.6:
                t = ratio / 0.6
                r = int(c1[0] + (c2[0] - c1[0]) * t)
                g = int(c1[1] + (c2[1] - c1[1]) * t)
                b = int(c1[2] + (c2[2] - c1[2]) * t)
            else:
                t = (ratio - 0.6) / 0.4
                r = int(c2[0] + (c3[0] - c2[0]) * t)
                g = int(c2[1] + (c3[1] - c2[1]) * t)
                b = int(c2[2] + (c3[2] - c2[2]) * t)
            bg_draw.rectangle([x, y, x + 8, y + 1], fill=(r, g, b, 255))
            
    margin = int(W * 0.04)
    mask = Image.new("L", (W, H), 0)
    mask_draw = ImageDraw.Draw(mask)
    
    if is_round:
        mask_draw.ellipse([margin, margin, W - margin, H - margin], fill=255)
    else:
        radius = int(W * 0.225)
        mask_draw.rounded_rectangle([margin, margin, W - margin, H - margin], radius=radius, fill=255)
    
    icon_bg = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    icon_bg.paste(bg, (0, 0), mask)
    
    # 微发光边框
    border_mask = Image.new("L", (W, H), 0)
    border_draw = ImageDraw.Draw(border_mask)
    if is_round:
        border_draw.ellipse([margin, margin, W - margin, H - margin], outline=255, width=int(scale * 3))
    else:
        border_draw.rounded_rectangle([margin, margin, W - margin, H - margin], radius=radius, outline=255, width=int(scale * 3))
    border_layer = Image.new("RGBA", (W, H), (255, 255, 255, 60))
    icon_bg.paste(border_layer, (0, 0), border_mask)
    
    # 车体阴影
    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    shadow_draw = ImageDraw.Draw(shadow)
    
    bus_w = int(W * 0.48)
    bus_h = int(H * 0.54)
    bus_l = int((W - bus_w) / 2)
    bus_t = int(H * 0.22)
    bus_r = bus_l + bus_w
    bus_b = bus_t + bus_h
    bus_radius = int(bus_w * 0.18)
    
    shadow_draw.rounded_rectangle(
        [bus_l, bus_t + int(scale * 10), bus_r, bus_b + int(scale * 14)],
        radius=bus_radius,
        fill=(10, 20, 50, 110)
    )
    shadow = shadow.filter(ImageFilter.GaussianBlur(radius=int(scale * 12)))
    icon_bg = Image.alpha_composite(icon_bg, shadow)
    
    bus_layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    bdraw = ImageDraw.Draw(bus_layer)
    
    # 车顶气动凸起
    roof_w = int(bus_w * 0.44)
    roof_h = int(scale * 14)
    roof_l = int(bus_l + (bus_w - roof_w) / 2)
    bdraw.rounded_rectangle([roof_l, bus_t - roof_h + int(scale*4), roof_l + roof_w, bus_t + int(scale*6)], radius=int(scale*6), fill=(230, 240, 255, 255))
    
    # 纯白主体车身
    bdraw.rounded_rectangle([bus_l, bus_t, bus_r, bus_b], radius=bus_radius, fill=(255, 255, 255, 255))
    
    # 全景风挡
    win_margin = int(bus_w * 0.08)
    win_l = bus_l + win_margin
    win_r = bus_r - win_margin
    win_t = bus_t + int(bus_h * 0.09)
    win_h = int(bus_h * 0.38)
    win_b = win_t + win_h
    win_radius = int(bus_radius * 0.7)
    bdraw.rounded_rectangle([win_l, win_t, win_r, win_b], radius=win_radius, fill=(15, 23, 42, 255))
    
    # 翡翠绿路线 LED 屏
    led_h = int(scale * 10)
    led_w = int((win_r - win_l) * 0.6)
    led_l = int(win_l + (win_r - win_l - led_w) / 2)
    led_t = win_t + int(scale * 8)
    bdraw.rounded_rectangle([led_l, led_t, led_l + led_w, led_t + led_h], radius=int(scale * 4), fill=(16, 185, 129, 230))
    
    # 风挡反光
    glare_poly = [
        (win_l + int(scale * 16), win_b - int(scale * 6)),
        (win_l + int(scale * 42), win_t + int(scale * 6)),
        (win_l + int(scale * 62), win_t + int(scale * 6)),
        (win_l + int(scale * 36), win_b - int(scale * 6)),
    ]
    bdraw.polygon(glare_poly, fill=(255, 255, 255, 45))
    
    # 装饰条
    grille_t = win_b + int(bus_h * 0.12)
    grille_h = int(scale * 8)
    grille_w = int(bus_w * 0.42)
    grille_l = int(bus_l + (bus_w - grille_w) / 2)
    bdraw.rounded_rectangle([grille_l, grille_t, grille_l + grille_w, grille_t + grille_h], radius=int(scale*4), fill=(203, 213, 225, 255))
    
    # 细长矩阵大灯
    light_w = int(bus_w * 0.16)
    light_h = int(scale * 11)
    light_y = win_b + int(bus_h * 0.15)
    left_light_l = bus_l + int(win_margin * 1.1)
    bdraw.rounded_rectangle([left_light_l, light_y, left_light_l + light_w, light_y + light_h], radius=int(scale*5), fill=(14, 165, 233, 255))
    right_light_r = bus_r - int(win_margin * 1.1)
    bdraw.rounded_rectangle([right_light_r - light_w, light_y, right_light_r, light_y + light_h], radius=int(scale*5), fill=(14, 165, 233, 255))
    
    # 黑色轮胎
    wheel_w = int(bus_w * 0.13)
    wheel_h = int(scale * 14)
    bdraw.rounded_rectangle([bus_l + int(bus_w * 0.08), bus_b - int(scale*6), bus_l + int(bus_w * 0.08) + wheel_w, bus_b + wheel_h], radius=int(scale*4), fill=(30, 41, 59, 255))
    bdraw.rounded_rectangle([bus_r - int(bus_w * 0.08) - wheel_w, bus_b - int(scale*6), bus_r - int(bus_w * 0.08), bus_b + wheel_h], radius=int(scale*4), fill=(30, 41, 59, 255))
    
    # 动感实时波浪轨道
    track_y = bus_b + int(H * 0.07)
    track_w = int(W * 0.62)
    track_l = int((W - track_w) / 2)
    bdraw.rounded_rectangle([track_l, track_y, track_l + track_w, track_y + int(scale * 6)], radius=int(scale * 3), fill=(255, 255, 255, 140))
    
    # 实时信号发射光点
    beacon_x = int(W * 0.68)
    beacon_y = track_y + int(scale * 3)
    beacon_r = int(scale * 12)
    bdraw.ellipse([beacon_x - beacon_r, beacon_y - beacon_r, beacon_x + beacon_r, beacon_y + beacon_r], fill=(16, 185, 129, 255))
    bdraw.ellipse([beacon_x - int(beacon_r*0.4), beacon_y - int(beacon_r*0.4), beacon_x + int(beacon_r*0.4), beacon_y + int(beacon_r*0.4)], fill=(255, 255, 255, 255))
    
    icon_final = Image.alpha_composite(icon_bg, bus_layer)
    return icon_final.resize((size, size), Image.Resampling.LANCZOS)

res_base = r"c:\Users\a3991\Documents\my_pro\clean_bus_app\app\src\main\res"

densities = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

for folder, s in densities.items():
    d = os.path.join(res_base, folder)
    os.makedirs(d, exist_ok=True)
    
    # 标准图标
    img_sq = create_modern_bus_canvas(s, is_round=False)
    img_sq.save(os.path.join(d, "ic_launcher.png"))
    
    # 圆形图标
    img_rd = create_modern_bus_canvas(s, is_round=True)
    img_rd.save(os.path.join(d, "ic_launcher_round.png"))
    print(f"Generated {folder}: {s}x{s}")

# 生成 drawable/ic_launcher.png 兜底
drawable_dir = os.path.join(res_base, "drawable")
os.makedirs(drawable_dir, exist_ok=True)
create_modern_bus_canvas(192, is_round=False).save(os.path.join(drawable_dir, "ic_launcher.png"))
create_modern_bus_canvas(192, is_round=True).save(os.path.join(drawable_dir, "ic_launcher_round.png"))

# 生成 512x512 高清母版到 screenshots
master = create_modern_bus_canvas(512, is_round=False)
master.save(r"c:\Users\a3991\Documents\my_pro\clean_bus_app\screenshots\app_icon_512.png")
print("All density icons generated successfully!")
