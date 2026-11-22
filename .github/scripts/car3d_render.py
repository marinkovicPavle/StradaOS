#!/usr/bin/env python3
"""Pravi kadrove za „3D" auto u StradaOS-u (CarSpin.java) iz .glb modela, Blenderom.

Upotreba (bpy iz pip-a, Python 3.11+):
    pip install bpy pillow
    python car3d_render.py model.glb app/src/main/assets/car3d --frames 48 --paint 0.30,0.31,0.32   # Iridium Silver 775 (linearne RGB vrednosti)

Kadar 0 je auto spreda; svaki sledeći kamera ide za 360/frames stepeni oko auta (smer kao u CarSpin.java).
Pozadina je providna (senka ispod auta samo uz --shadow). Svi kadrovi se seku na isti okvir i čuvaju kao WebP
sa providnošću (Android 4.3+). Boja laka menja materijale čije ime sadrži --paint-material.
"""
import argparse
import math
import os
import sys
import tempfile


def render(a):
    import bpy
    import mathutils

    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=a.model)
    sc = bpy.context.scene
    for o in list(bpy.data.objects):
        if o.type in ('LIGHT', 'CAMERA'):
            bpy.data.objects.remove(o)
    mesh = [o for o in bpy.data.objects if o.type == 'MESH']
    mn = mathutils.Vector((1e9,) * 3)
    mx = -mn
    for o in mesh:
        for c in o.bound_box:
            w = o.matrix_world @ mathutils.Vector(c)
            mn = mathutils.Vector(map(min, mn, w))
            mx = mathutils.Vector(map(max, mx, w))
    ctr = (mn + mx) / 2
    size = (mx - mn).length

    # boja laka
    if a.paint:
        rgb = [float(v) for v in a.paint.split(',')]
        for m in bpy.data.materials:
            if a.paint_material.lower() in m.name.lower() and m.use_nodes:
                for n in m.node_tree.nodes:
                    if n.type == 'BSDF_PRINCIPLED':
                        bc = n.inputs['Base Color']
                        for l in list(bc.links):   # tekstura boje bi pregazila izabranu boju
                            m.node_tree.links.remove(l)
                        bc.default_value = (rgb[0], rgb[1], rgb[2], 1)
                        n.inputs['Metallic'].default_value = 0.8
                        n.inputs['Roughness'].default_value = 0.3
                        if 'Coat Weight' in n.inputs:
                            n.inputs['Coat Weight'].default_value = 1.0

    # senka ispod auta (ravan koja hvata samo senku): podrazumevano isključena, na tamnoj kartici pravi svetao oreol
    if a.shadow:
        bpy.ops.mesh.primitive_plane_add(size=size * 3, location=(ctr.x, ctr.y, mn.z + 0.002))
        bpy.context.active_object.is_shadow_catcher = True

    sc.render.engine = 'CYCLES'
    sc.cycles.device = 'CPU'
    sc.cycles.samples = a.samples
    sc.cycles.use_denoising = True
    sc.render.film_transparent = True
    sc.view_settings.exposure = -0.6   # srebrna boja ne sme da izgleda kao bela
    sc.render.resolution_x = a.width
    sc.render.resolution_y = int(a.width * 0.6)
    sc.render.image_settings.file_format = 'PNG'
    sc.render.image_settings.color_mode = 'RGBA'
    world = bpy.data.worlds.new('w')
    sc.world = world
    world.use_nodes = True
    bg = world.node_tree.nodes['Background']
    bg.inputs[0].default_value = (0.85, 0.87, 0.9, 1)
    bg.inputs[1].default_value = 0.8

    def light(name, loc, energy, s):
        l = bpy.data.lights.new(name, 'AREA')
        l.energy = energy
        l.size = s
        o = bpy.data.objects.new(name, l)
        sc.collection.objects.link(o)
        o.location = loc
        o.rotation_euler = (ctr - mathutils.Vector(loc)).to_track_quat('-Z', 'Y').to_euler()

    # svetla prate kameru nisu potrebna: meko svetlo odozgo i sa strana
    light('top', (ctr.x, ctr.y, ctr.z + 6), 1200, 7)
    light('l1', (ctr.x + 6, ctr.y - 4, ctr.z + 3), 500, 5)
    light('l2', (ctr.x - 6, ctr.y + 4, ctr.z + 3), 500, 5)

    cam = bpy.data.cameras.new('c')
    cam.lens = 70
    co = bpy.data.objects.new('c', cam)
    sc.collection.objects.link(co)
    sc.camera = co
    dist = size * 2.5
    e = math.radians(a.elevation)
    out = []
    for i in range(a.frames):
        r = math.radians(360.0 * i / a.frames)
        # model gleda ka -Y: kadar 0 je kamera ispred auta
        co.location = (ctr.x + dist * math.cos(e) * math.sin(r), ctr.y - dist * math.cos(e) * math.cos(r), ctr.z + dist * math.sin(e))
        co.rotation_euler = (ctr - co.location).to_track_quat('-Z', 'Y').to_euler()
        f = os.path.join(a.tmp, '%02d.png' % i)
        sc.render.filepath = f
        bpy.ops.render.render(write_still=True)
        out.append(f)
    return out


def pack(files, dest, quality):
    from PIL import Image
    ims = [Image.open(f).convert('RGBA') for f in files]
    box = None
    for im in ims:
        b = im.getchannel('A').point(lambda v: 255 if v > 8 else 0).getbbox()
        if b:
            box = b if box is None else (min(box[0], b[0]), min(box[1], b[1]), max(box[2], b[2]), max(box[3], b[3]))
    os.makedirs(dest, exist_ok=True)
    for old in os.listdir(dest):
        if old.endswith('.webp'):
            os.remove(os.path.join(dest, old))
    total = 0
    for i, im in enumerate(ims):
        p = os.path.join(dest, '%02d.webp' % i)
        im.crop(box).save(p, 'WEBP', quality=quality, method=6)
        total += os.path.getsize(p)
    print('kadrova: %d, okvir %s, ukupno %.0f KB' % (len(ims), box, total / 1024))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('model')
    ap.add_argument('dest')
    ap.add_argument('--frames', type=int, default=48)
    ap.add_argument('--width', type=int, default=720)
    ap.add_argument('--samples', type=int, default=48)
    ap.add_argument('--elevation', type=float, default=14)
    ap.add_argument('--paint', default='')
    ap.add_argument('--paint-material', default='color')
    ap.add_argument('--quality', type=int, default=82)
    ap.add_argument('--shadow', action='store_true')
    a = ap.parse_args()
    a.tmp = tempfile.mkdtemp(prefix='car3d_')
    pack(render(a), a.dest, a.quality)


if __name__ == '__main__':
    sys.exit(main())
